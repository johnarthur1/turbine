/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.binder;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.TurbineModifier;
import java.util.ArrayDeque;

/** Type hierarchy binding. */
public class HierarchyBinder {

  /** Binds the type hierarchy (superclasses and interfaces) for a single class. */
  public static SourceHeaderBoundClass bind(
      PackageSourceBoundClass base, Env<? extends HeaderBoundClass> env) {
    Tree.TyDecl decl = base.decl();

    int access = 0;
    for (TurbineModifier m : decl.mods()) {
      access |= m.flag();
    }
    switch (decl.tykind()) {
      case CLASS:
        access |= TurbineFlag.ACC_SUPER;
        break;
      case INTERFACE:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE;
        break;
      case ENUM:
        access |= TurbineFlag.ACC_ENUM | TurbineFlag.ACC_SUPER;
        break;
      case ANNOTATION:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ANNOTATION;
        break;
      default:
        throw new AssertionError(decl.tykind());
    }

    TurbineVisibility visibility;
    // types declared in interfaces  annotations are implicitly public (JLS 9.5)
    if (enclosedByInterface(env, base)) {
      visibility = TurbineVisibility.PUBLIC;
    } else {
      visibility = TurbineVisibility.fromAccess(access);
    }

    if ((access & TurbineFlag.ACC_STATIC) == 0) {
      // nested enums (JLS 8.9) and types nested within interfaces and annotations (JLS 9.5) are
      // implicitly static
      if (base.kind() == TurbineTyKind.ENUM || enclosedByInterface(env, base)) {
        access |= TurbineFlag.ACC_STATIC;
      }
    }

    if (decl.tykind() == TurbineTyKind.INTERFACE) {
      access |= TurbineFlag.ACC_ABSTRACT;
    }

    ClassSymbol superclass;
    if (decl.xtnds().isPresent()) {
      superclass = resolveClass(env, base.scope(), base.owner(), decl.xtnds().get());
    } else {
      switch (decl.tykind()) {
        case ENUM:
          superclass = ClassSymbol.ENUM;
          if (isEnumAbstract(decl)) {
            access |= TurbineFlag.ACC_ABSTRACT;
          } else {
            access |= TurbineFlag.ACC_FINAL;
          }
          break;
        case INTERFACE:
        case ANNOTATION:
        case CLASS:
          // TODO(b/31185757): this doesn't handle compiling Object
          superclass = ClassSymbol.OBJECT;
          break;
        default:
          throw new AssertionError(decl.tykind());
      }
    }

    ImmutableList.Builder<ClassSymbol> interfaces = ImmutableList.builder();
    if (!decl.impls().isEmpty()) {
      for (Tree.ClassTy i : decl.impls()) {
        ClassSymbol result = resolveClass(env, base.scope(), base.owner(), i);
        if (result == null) {
          throw new AssertionError(i);
        }
        interfaces.add(result);
      }
    } else {
      if (decl.tykind() == TurbineTyKind.ANNOTATION) {
        interfaces.add(ClassSymbol.ANNOTATION);
      }
    }
    return new SourceHeaderBoundClass(base, superclass, interfaces.build(), visibility, access);
  }

  /** Returns true if the given type is declared in an interface. */
  private static boolean enclosedByInterface(Env<? extends HeaderBoundClass> env, BoundClass c) {
    while (c.owner() != null) {
      c = env.get(c.owner());
      switch (c.kind()) {
        case INTERFACE:
        case ANNOTATION:
          return true;
        default:
          break;
      }
    }
    return false;
  }

  /**
   * If any enum constants have a class body (which is recorded in the parser by setting ENUM_IMPL),
   * the class generated for the enum needs to have ACC_ABSTRACT set.
   */
  private static boolean isEnumAbstract(Tree.TyDecl decl) {
    for (Tree t : decl.members()) {
      if (t.kind() != Tree.Kind.VAR_DECL) {
        continue;
      }
      Tree.VarDecl var = (Tree.VarDecl) t;
      if (!var.mods().contains(TurbineModifier.ENUM_IMPL)) {
        continue;
      }
      return true;
    }
    return false;
  }

  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   */
  public static ClassSymbol resolveClass(
      Env<? extends HeaderBoundClass> env,
      CompoundScope enclscope,
      ClassSymbol owner,
      Tree.ClassTy ty) {
    // flatten a left-recursive qualified type name to its component simple names
    // e.g. Foo<Bar>.Baz -> ["Foo", "Bar"]
    ArrayDeque<String> flat = new ArrayDeque<>();
    for (Tree.ClassTy curr = ty; curr != null; curr = curr.base().orNull()) {
      flat.addFirst(curr.name());
    }
    // Resolve the base symbol in the qualified name.
    LookupResult result = lookup(env, enclscope, owner, new LookupKey(flat));
    if (result == null) {
      return null;
    }
    // Resolve pieces in the qualified name referring to member types.
    // This needs to consider member type declarations inherited from supertypes and interfaces.
    ClassSymbol sym = result.sym();
    for (String bit : result.remaining()) {
      sym = Resolve.resolve(env, sym, bit);
      if (sym == null) {
        return null;
      }
    }
    return sym;
  }

  /** Resolve a qualified type name to a symbol. */
  public static LookupResult lookup(
      Env<? extends HeaderBoundClass> env,
      CompoundScope parent,
      ClassSymbol sym,
      LookupKey lookup) {
    // Handle any lexically enclosing class declarations (if we're binding a member class).
    // We could build out scopes for this, but it doesn't seem worth it. (And sharing the scopes
    // with other members of the same enclosing declaration would be complicated.)
    for (ClassSymbol curr = sym; curr != null; curr = env.get(curr).owner()) {
      ClassSymbol result = Resolve.resolve(env, curr, lookup.first());
      if (result != null) {
        return new LookupResult(result, lookup);
      }
    }
    // Fall back to the top-level scopes for the compilation unit (imports, same package, then
    // qualified name resolution).
    return parent.lookup(lookup);
  }
}