/*******************************************************************************
 * Copyright (c) 2009 Codehaus.org, SpringSource, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andy Clement        - Initial API and implementation
 *     Andrew Eisenberg - Additional work
 *******************************************************************************/
package org.codehaus.jdt.groovy.internal.compiler.ast;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.ResolveVisitor;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding;
import org.eclipse.jdt.internal.compiler.lookup.BaseTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedMethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;

/**
 * An extension to the standard groovy ResolveVisitor that can ask JDT for types when groovy cannot find them. A groovy project in
 * Eclipse is typically configured with very limited knowledge of its dependencies so most lookups are through JDT.
 * 
 * Resolver lifecycle:<br>
 * The JDTResolver is created at the same time as the (Groovy) CompilationUnit. The CompilationUnit knows about all the code that is
 * to be compiled together. The resolver maintains a cache from Binding to JDTClassNode and the cache contents have the same
 * lifetime as the JDTResolver. The resolver does type lookups through the currently active scope - the active scope is set when the
 * method 'commencingResolution()' is called. This is called by the superclass (ResolveVisitor) when it is about to start resolving
 * every reference in a type.
 * 
 * @author Andy Clement
 */
@SuppressWarnings("restriction")
public class JDTResolver extends ResolveVisitor {

	// For resolver debugging
	private static final boolean debug = false;

	// Arbitrary selection of common types
	private static Map<String, ClassNode> commonTypes = new HashMap<String, ClassNode>();
	static {
		commonTypes.put("java.lang.Object", ClassHelper.OBJECT_TYPE);
		commonTypes.put("java.lang.String", ClassHelper.STRING_TYPE);
		commonTypes.put("java.lang.Class", ClassHelper.CLASS_Type);
	}

	private Stack<GenericsType[]> memberGenericsCurrentlyActive = new Stack<GenericsType[]>();
	private Stack<GenericsType[]> typeGenericsCurrentlyActive = new Stack<GenericsType[]>();

	void pushMemberGenerics(GenericsType[] generics) {
		memberGenericsCurrentlyActive.push(generics);
	}

	void popMemberGenerics() {
		memberGenericsCurrentlyActive.pop();
	}

	public void pushTypeGenerics(GenericsType[] genericsTypes) {
		typeGenericsCurrentlyActive.push(genericsTypes);
	}

	public void popTypeGenerics() {
		typeGenericsCurrentlyActive.pop();
	}

	// By recording what is currently in progress in terms of creation, we avoid recursive problems (like Enum<E extends Enum<E>>)
	private Map<TypeBinding, JDTClassNode> inProgress = new HashMap<TypeBinding, JDTClassNode>();

	private Stack<JDTClassNode> inProgressStack = new Stack<JDTClassNode>();

	// Type references are resolved through the 'activeScope'. This ensures visibility rules are obeyed - just because a
	// type exists does not mean it is visible to some other type and scope lookups verify this.
	private GroovyCompilationUnitScope activeScope = null;

	// map of scopes in which resolution can happen
	private Map<ClassNode, GroovyTypeDeclaration> scopes = new HashMap<ClassNode, GroovyTypeDeclaration>();

	// Cache from bindings to JDTClassNodes to avoid unnecessary JDTClassNode creation
	private Map<Binding, JDTClassNode> nodeCache = new HashMap<Binding, JDTClassNode>();

	public JDTResolver(CompilationUnit groovyCompilationUnit) {
		super(groovyCompilationUnit);
	}

	/**
	 * resolveFromModule() - look at other types in the same source file (no need to talk to JDT)
	 */
	@Override
	protected boolean resolveFromModule(ClassNode type, boolean testModuleImports) {
		boolean foundit = super.resolveFromModule(type, testModuleImports);
		recordDependency(type.getName());
		if (debug) {
			log("resolveFromModule", type, foundit);
		}
		return foundit;
	}

	/**
	 * resolveFromCompileUnit() - look at other source types in this CompilationUnit (ie. this 'project' in JDT terms).
	 */
	@Override
	protected boolean resolveFromCompileUnit(ClassNode type) {
		boolean foundit = super.resolveFromCompileUnit(type);
		recordDependency(type.getName());
		if (debug) {
			log("resolveFromCompileUnit", type, foundit);
		}

		if (foundit) {
			return true;
		}

		// Ask JDT for a source file, visible from this scope

		String typename = type.getName();
		ClassNode node = getScope().lookupClassNodeForSource(typename, this);
		if (debug) {
			log("resolveFromCompileUnit (jdt) ", type, node != null);
		}
		if (node != null) {
			type.setRedirect(node);
			return true;
		} else {
			return false;
		}

		// CHECK_IT(redirect);
	}

	@Override
	protected boolean resolveFromDefaultImports(ClassNode type, boolean testDefaultImports) {
		boolean foundit = super.resolveFromDefaultImports(type, testDefaultImports);
		recordDependency(type.getName());
		if (debug) {
			log("resolveFromDefaultImports", type, foundit);
		}
		return foundit;
	}

	@Override
	protected boolean resolveFromStaticInnerClasses(ClassNode type, boolean testStaticInnerClasses) {
		boolean foundit = super.resolveFromStaticInnerClasses(type, testStaticInnerClasses);
		recordDependency(type.getName());
		if (debug) {
			log("resolveFromStaticInnerClasses", type, foundit);
		}
		return foundit;
		// FIXASC (M3) anything special for inner types?
	}

	// @Override
	// protected boolean resolveStaticInner(ClassNode type) {
	// boolean foundit = super.resolveStaticInner(type);
	// recordDependency(type.getName());
	// if (debug) {
	// log("resolveStaticInner", type, foundit);
	// }
	// return foundit;
	// }

	/**
	 * resolveFromClassCache() - no point in asking, the cache does not get populated.
	 */
	@Override
	protected boolean resolveFromClassCache(ClassNode type) {
		return false;
	}

	/**
	 * resolveToClass() - this would normally ask the groovy class loader, but we don't want to do that - let JDT find everything.
	 */
	@Override
	protected boolean resolveToClass(ClassNode type) {
		String typename = type.getName();
		ClassNode node = getScope().lookupClassNodeForBinary(typename, this);
		if (debug) {
			log("resolveToClass (jdt)", type, node != null);
		}
		if (node != null) {
			type.setRedirect(node);
			return true;
		} else {
			return false;
		}
		// boolean foundit = super.resolveToClass(type);
		// if (debug) {
		// log("resolveToClass", type, foundit);
		// }
		// return foundit;
	}

	/**
	 * resolveToScript() - ask the groovy class loader. We don't want to do this - let JDT find everything.
	 */
	@Override
	protected boolean resolveToScript(ClassNode type) {
		return false;
	}

	@Override
	protected boolean resolve(ClassNode type, boolean testModuleImports, boolean testDefaultImports, boolean testStaticInnerClasses) {
		if (type.getName().charAt(0) == 'j') {
			ClassNode commonRedirect = commonTypes.get(type.getName());
			if (commonRedirect != null) {
				type.setRedirect(commonRedirect);
				return true;
			}
		}
		return super.resolve(type, testModuleImports, testDefaultImports, testStaticInnerClasses);
	}

	// FIXASC (M2) callers could check if it is a 'funky' type before always recording a depedency
	// by 'funky' I mean that the type was constructed just to try something (org.foo.bar.java$lang$Wibble doesn't want recording!)
	private void recordDependency(String typename) {
		GroovyCompilationUnitScope gcuScope = getScope();
		// System.err.println("Recording reference from " + toShortString(gcuScope) + " to " + typename);
		if (typename.indexOf(".") != -1) {
			gcuScope.recordQualifiedReference(CharOperation.splitOn('.', typename.toCharArray()));
		} else {
			gcuScope.recordSimpleReference(typename.toCharArray());
		}
	}

	/**
	 * Convert from a JDT Binding to a Groovy ClassNode
	 */
	ClassNode convertToClassNode(TypeBinding jdtBinding) {
		if (inProgress.containsKey(jdtBinding)) {
			return inProgress.get(jdtBinding);
		}
		JDTClassNode existingNode = nodeCache.get(jdtBinding);
		if (existingNode != null) {
			if (debug) {
				log("Using cached JDTClassNode for binding " + new String(jdtBinding.readableName()));
			}
			return existingNode;
		}

		if (debug) {
			log("createJDTClassNode: Building new JDTClassNode for binding " + new String(jdtBinding.readableName()));
		}

		ClassNode jdtNode = createJDTClassNode(jdtBinding);
		return jdtNode;
	}

	/**
	 * Create a Groovy ClassNode that represents the JDT TypeBinding. Build the basic structure, mark it as 'in progress' and then
	 * continue with initialization. This allows self referential generic declarations.
	 * 
	 * @param jdtBinding the JDT binding for which to create a ClassNode
	 * @return the new ClassNode, of type JDTClassNode
	 */
	private ClassNode createJDTClassNode(TypeBinding jdtBinding) {
		ClassNode classNode = createClassNode(jdtBinding);
		if (classNode instanceof JDTClassNode) {
			JDTClassNode jdtNode = (JDTClassNode) classNode;
			inProgress.put(jdtBinding, jdtNode);
			inProgressStack.push(jdtNode);
			jdtNode.setupGenerics();
			inProgressStack.pop();
			inProgress.remove(jdtBinding);
			nodeCache.put(jdtBinding, jdtNode);
		}
		return classNode;
	}

	/**
	 * Create a ClassNode based on the type of the JDT binding, this takes account of all the possible kinds of JDT binding.
	 */
	private ClassNode createClassNode(TypeBinding jdtTypeBinding) {
		// Simple cases first for now:
		if (jdtTypeBinding instanceof WildcardBinding) {
			return createClassNodeForWildcardBinding((WildcardBinding) jdtTypeBinding);
		} else if (jdtTypeBinding instanceof BaseTypeBinding) {
			return createClassNodeForPrimitiveBinding((BaseTypeBinding) jdtTypeBinding);
		} else if (jdtTypeBinding instanceof ArrayBinding) {
			return createClassNodeForArrayBinding((ArrayBinding) jdtTypeBinding);
		} else if (jdtTypeBinding instanceof TypeVariableBinding) {
			String typeVariableName = new String(jdtTypeBinding.sourceName());

			TypeVariableBinding typeVariableBinding = (TypeVariableBinding) jdtTypeBinding;
			if (typeVariableBinding.declaringElement instanceof SourceTypeBinding) {
				GenericsType[] genericTypes = typeGenericsCurrentlyActive.peek();
				GenericsType matchingGenericType = findMatchingGenericType(genericTypes, typeVariableName);
				if (matchingGenericType != null) {
					ClassNode newNode = ClassHelper.makeWithoutCaching(typeVariableName);
					newNode.setRedirect(matchingGenericType.getType());
					newNode.setGenericsTypes(new GenericsType[] { matchingGenericType });
					newNode.setGenericsPlaceHolder(true);
					return newNode;
				}

				// What does it means if we are here?
				// it means we've encountered a type variable but this class doesn't declare it.
				// So far this has been seen in the case where a synthetic binding is created for
				// a bridge method from a supertype. It appears what we can do here is collapse
				// that type variable to its bound (as this is meant to be a bridge method)
				// But what if it was bound by something a little higher up?
				// What other cases are there to worry about?

				if (typeVariableBinding.firstBound == null) {
					return ClassHelper.OBJECT_TYPE;
				} else {
					// c'est vrai?
					return convertToClassNode(typeVariableBinding.firstBound);
				}
				// throw new GroovyEclipseBug("Cannot find type variable on source type declaring element "
				// + typeVariableBinding.declaringElement);
			} else if (typeVariableBinding.declaringElement instanceof BinaryTypeBinding) {
				GenericsType[] genericTypes = convertToClassNode(((BinaryTypeBinding) typeVariableBinding.declaringElement))
						.getGenericsTypes();
				GenericsType matchingGenericType = findMatchingGenericType(genericTypes, typeVariableName);
				if (matchingGenericType != null) {
					ClassNode newNode = ClassHelper.makeWithoutCaching(typeVariableName);
					newNode.setRedirect(matchingGenericType.getType());
					newNode.setGenericsTypes(new GenericsType[] { matchingGenericType });
					newNode.setGenericsPlaceHolder(true);
					return newNode;
				}
				throw new GroovyEclipseBug("Cannot find type variable on type declaring element "
						+ typeVariableBinding.declaringElement);
			} else if (typeVariableBinding.declaringElement instanceof ParameterizedMethodBinding
					|| typeVariableBinding.declaringElement instanceof MethodBinding) {
				GenericsType[] genericTypes = memberGenericsCurrentlyActive.peek();
				GenericsType matchingGenericType = findMatchingGenericType(genericTypes, typeVariableName);
				if (matchingGenericType != null) {
					ClassNode newNode = ClassHelper.makeWithoutCaching(typeVariableName);
					newNode.setRedirect(matchingGenericType.getType());
					newNode.setGenericsTypes(new GenericsType[] { matchingGenericType });
					newNode.setGenericsPlaceHolder(true);
					return newNode;
				}
				throw new GroovyEclipseBug("Cannot find type variable on method declaring element "
						+ typeVariableBinding.declaringElement);
			}
			throw new GroovyEclipseBug("Unexpected type variable reference.  Declaring element is "
					+ typeVariableBinding.declaringElement);
			// FIXASC (M2) unwrapping turned off - remove it?
			// } else if (jdtTypeBinding instanceof SourceTypeBinding) {
			// // check if it is a placeholder for a groovy type
			// // FIXASC (M2) only unwrap if same Groovy CompilationUnit?
			// SourceTypeBinding sourceTypeBinding = (SourceTypeBinding) jdtTypeBinding;
			// if (sourceTypeBinding.scope != null) {
			// // FIXASC (M2) why would scope ever be null? (if there is an error elsewhere!)
			// TypeDeclaration typeDeclaration = sourceTypeBinding.scope.referenceContext;
			// if (typeDeclaration instanceof GroovyTypeDeclaration) {
			// GroovyTypeDeclaration groovyTypeDeclaration = (GroovyTypeDeclaration) typeDeclaration;
			// ClassNode wrappedNode = groovyTypeDeclaration.getClassNode();
			// return wrappedNode;
			// }
			// }
			// // should be building another lightweight JDT ClassNode
			// // return convertToClassNode(jdtTypeBinding);// resolver.createJDTClassNode(jdtTypeBinding);
			// return new JDTClassNode((SourceTypeBinding) jdtTypeBinding, this);
		} else if (jdtTypeBinding instanceof ReferenceBinding) {
			// } else if (jdtTypeBinding instanceof RawTypeBinding) {
			// return configureParameterizedType((ParameterizedTypeBinding) jdtTypeBinding);
			// } else if (jdtTypeBinding instanceof ParameterizedTypeBinding) {
			// return configureParameterizedType((ParameterizedTypeBinding) jdtTypeBinding);
			if (jdtTypeBinding.id == TypeIds.T_JavaLangObject) {
				return ClassHelper.OBJECT_TYPE;
			}
			return new JDTClassNode((ReferenceBinding) jdtTypeBinding, this);
			// throw new GroovyBugError("Should have treated this as something else! " + jdtTypeBinding.getClass());
			// return createClassNode(jdtTypeBinding);// resolver.createJDTClassNode(jdtTypeBinding);
		} else {
			throw new GroovyEclipseBug("Unable to convert this binding: " + jdtTypeBinding.getClass());
		}
	}

	private GenericsType findMatchingGenericType(GenericsType[] genericTypes, String typeVariableName) {
		if (genericTypes != null) {
			for (GenericsType genericType : genericTypes) {
				if (genericType.getName().equals(typeVariableName)) {
					return genericType;
				}
			}
		}
		return null;
	}

	ClassNode createClassNodeForArrayBinding(ArrayBinding arrayBinding) {
		int dims = arrayBinding.dimensions;
		ClassNode classNode = convertToClassNode(arrayBinding.leafComponentType);
		while (dims > 0) {
			classNode = new ClassNode(classNode);
			dims--;
		}
		return classNode;
	}

	ClassNode createClassNodeForPrimitiveBinding(BaseTypeBinding jdtBinding) {
		switch (jdtBinding.id) {
			case TypeIds.T_boolean:
				return ClassHelper.boolean_TYPE;
			case TypeIds.T_char:
				return ClassHelper.char_TYPE;
			case TypeIds.T_byte:
				return ClassHelper.byte_TYPE;
			case TypeIds.T_short:
				return ClassHelper.short_TYPE;
			case TypeIds.T_int:
				return ClassHelper.int_TYPE;
			case TypeIds.T_long:
				return ClassHelper.long_TYPE;
			case TypeIds.T_double:
				return ClassHelper.double_TYPE;
			case TypeIds.T_float:
				return ClassHelper.float_TYPE;
			case TypeIds.T_void:
				return ClassHelper.VOID_TYPE;
			default:
				throw new GroovyEclipseBug("Don't know what this is: " + jdtBinding);
		}
	}

	private ClassNode[] convertToClassNodes(TypeBinding[] typeBindings) {
		if (typeBindings.length == 0) {
			return null;
		}
		ClassNode[] nodes = new ClassNode[typeBindings.length];
		for (int i = 0; i < typeBindings.length; i++) {
			nodes[i] = convertToClassNode(typeBindings[i]);
		}
		return nodes;
	}

	private ClassNode createClassNodeForWildcardBinding(WildcardBinding wildcardBinding) {
		// FIXASC (M2) could use LazyGenericsType object here
		ClassNode base = ClassHelper.makeWithoutCaching("?");
		ClassNode lowerBound = null;
		ClassNode[] allUppers = null;
		if (wildcardBinding.boundKind == Wildcard.EXTENDS) {
			ClassNode firstUpper = convertToClassNode(wildcardBinding.bound);
			ClassNode[] otherUppers = (wildcardBinding.otherBounds == null ? null
					: convertToClassNodes(wildcardBinding.otherBounds));
			if (otherUppers == null) {
				allUppers = new ClassNode[] { firstUpper };
			} else {
				allUppers = new ClassNode[otherUppers.length + 1];
				System.arraycopy(otherUppers, 0, allUppers, 1, otherUppers.length);
				allUppers[0] = firstUpper;
			}
		} else if (wildcardBinding.boundKind == Wildcard.SUPER) {
			lowerBound = convertToClassNode(wildcardBinding.bound);
		} else {
			assert (wildcardBinding.boundKind == Wildcard.UNBOUND);
			return JDTClassNode.unboundWildcard;
		}
		GenericsType t = new GenericsType(base, allUppers, lowerBound);
		t.setWildcard(true);
		ClassNode ref = ClassHelper.makeWithoutCaching(Object.class, false);
		ref.setGenericsTypes(new GenericsType[] { t });
		return ref;
	}

	/**
	 * Called when a resolvevisitor is commencing resolution for a type - allows us to setup the JDTResolver to point at the right
	 * scope for resolutionification. If not able to find a scope, that is a serious problem!
	 */
	@Override
	protected void commencingResolution() {
		GroovyTypeDeclaration gtDeclaration = scopes.get(this.currentClass);
		activeScope = null;
		if (gtDeclaration == null) {
			GroovyEclipseBug geb = new GroovyEclipseBug("JDTResolver.configure(): no declaration found for class " + currentClass);
			geb.printStackTrace();
			throw geb;
		}
		if (gtDeclaration.scope == null) {
			GroovyEclipseBug geb = new GroovyEclipseBug("JDTResolver.configure(): declaration found, but no scope found for "
					+ currentClass);
			geb.printStackTrace();
			throw geb;
		}
		activeScope = (GroovyCompilationUnitScope) gtDeclaration.scope.compilationUnitScope();
		if (debug) {
			System.err.println("Resolver: commencing resolution for " + this.currentClass.getName());
		}
	}

	private GroovyCompilationUnitScope getScope() {
		return activeScope;
	}

	private void log(String string) {
		System.err.println("Resolver: " + string);
	}

	// FIXASC (M2) can the relationship here from classNode to scope be better preserved to remove the need for this map?
	/**
	 * When recorded, the jdt resolver will be able to (later on) navigate from the classnode back to the JDT scope that should be
	 * used.
	 */
	public void record(GroovyTypeDeclaration gtDeclaration) {
		scopes.put(gtDeclaration.getClassNode(), gtDeclaration);
	}

	private void log(String string, ClassNode type, boolean foundit) {
		System.err.println("Resolver: " + string + " " + type.getName() + "  ?" + foundit);
	}

}
