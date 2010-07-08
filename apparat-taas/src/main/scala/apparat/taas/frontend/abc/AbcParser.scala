/*
 * This file is part of Apparat.
 *
 * Apparat is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Apparat is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Apparat. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2009 Joa Ebert
 * http://www.joa-ebert.com/
 *
 */
package apparat.taas.frontend.abc

import apparat.taas.ast._
import collection.mutable.ListBuffer
import apparat.abc._

/**
 * @author Joa Ebert
 */
protected[abc] class AbcParser(ast: TaasAST, abc: Abc, unit: TaasUnit) {
	private implicit val implicitAST = ast
	
	def parseAbc(): Unit = parseAbc(unit, abc)
	def parseAbc(abc: Abc): Unit = parseAbc(unit, abc)
	def parseAbc(unit: TaasUnit, abc: Abc): Unit = {
		abc.scripts foreach parseScript
	}

	def parseScript(script: AbcScript) = {
		script.traits foreach {
			case AbcTraitClass(name, _, nominalType, _) => {
				val pckg = packageOf(nominalType.inst.name.namespace.name)
				pckg.definitions += parseNominal(nominalType)
			}
			case anyMethod: AbcTraitAnyMethod => {
				val pckg = packageOf(anyMethod.name.namespace.name)
				pckg.definitions += {
					val method = parseMethod(None, anyMethod, true)
					TaasFunction(method.name,  method.namespace, method)
				}
			}
			case anySlot: AbcTraitAnySlot => {
				val pckg = packageOf(anySlot.name.namespace.name)
				pckg.definitions += parseSlot(anySlot, true)
			}
			case other => error("Unexpected trait: " + other)
		}
	}

	def parseNominal(nominal: AbcNominalType) = {
		val someNominal = Some(nominal)
		val base = nominal.inst.base match {
			case Some(base) => Some(name2type(base))
			case None => None
		}

		var methods = ListBuffer.empty[TaasMethod]

		methods ++= nominal.inst.traits collect {
			case methodTrait: AbcTraitAnyMethod => {
				parseMethod(someNominal, methodTrait, false)
			}
		}

		val interfaces = ListBuffer.empty[TaasType] ++ (nominal.inst.interfaces map { name2type _ })

		if(nominal.inst.isInterface) {
			TaasInterface(
				nominal.inst.name.name,
				nominal.inst.name.namespace,
				base,
				methods,
				interfaces
				)
		} else {
			val fields = ListBuffer.empty[TaasField]

			methods ++= nominal.klass.traits collect {
				case methodTrait: AbcTraitAnyMethod => parseMethod(someNominal, methodTrait, true)
			}

			fields ++= nominal.inst.traits collect {
				case slotTrait: AbcTraitAnySlot => parseSlot(slotTrait, false)
			}

			fields ++= nominal.klass.traits collect {
				case slotTrait: AbcTraitAnySlot => parseSlot(slotTrait, true)
			}

			TaasClass(nominal.inst.name.name,
				nominal.inst.name.namespace,
				nominal.inst.isFinal,
				!nominal.inst.isSealed,
				parseMethod(someNominal, nominal.klass.init, true, true),
				parseMethod(someNominal, nominal.inst.init, false, true),
				base,
				methods,
				fields,
				interfaces)
		}
	}

	def parseMethod(scope: Option[AbcNominalType], methodTrait: AbcTraitAnyMethod, isStatic: Boolean): TaasMethod = {
		TaasMethod(
			methodTrait.name.name,
			methodTrait.name.namespace,
			methodTrait.method.returnType,
			parseParameters(methodTrait.method.parameters),
			isStatic,
			methodTrait.isFinal,
			methodTrait.method.isNative,
			method2code(scope, isStatic, methodTrait.method))
	}

	def parseMethod(scope: Option[AbcNominalType], method: AbcMethod, isStatic: Boolean, isFinal: Boolean): TaasMethod = {
		TaasMethod(
			method.name,
			TaasPublic,
			method.returnType,
			parseParameters(method.parameters),
			isStatic,
			isFinal,
			method.isNative,
			method2code(scope, isStatic, method))
	}

	def parseParameters(parameters: Array[AbcMethodParameter]) = ListBuffer((parameters map parseParameter): _*)

	def parseParameter(parameter: AbcMethodParameter) = TaasParameter(parameter.typeName, parameter.optionalVal)

	def parseSlot(slotTrait: AbcTraitAnySlot, isStatic: Boolean) = slotTrait match {
		case AbcTraitSlot(name, _, typeName, _, _, _) => TaasSlot(name.name, name.namespace, typeName, isStatic)
		case AbcTraitConst(name, _, typeName, _, _, _) => TaasConstant(name.name, name.namespace, typeName, isStatic)
	}

	private def packageOf(namespace: Symbol) = {
		unit.children find {
			case TaasPackage(name, _) if namespace == name => true
			case TaasPackage(_, _) => false
		} match {
			case Some(namespace) => namespace
			case None => {
				val result = TaasPackage(namespace, ListBuffer.empty)
				unit.children prepend result
				result
			}
		}
	}

	private implicit def ns2ns(namespace: AbcNamespace): TaasNamespace = {
		namespace.kind match {
			case AbcNamespaceKind.Package => TaasPublic
			case AbcNamespaceKind.Explicit => error("Explicit")
			case AbcNamespaceKind.Namespace => TaasExplicit(namespace.name)
			case AbcNamespaceKind.PackageInternal => TaasInternal
			case AbcNamespaceKind.Private => TaasPrivate
			case AbcNamespaceKind.Protected => TaasProtected
			case AbcNamespaceKind.StaticProtected => TaasProtected
		}
	}

	private implicit def name2type(name: AbcName): TaasType = {
		if(name == AbcConstantPool.EMPTY_NAME) TaasAnyType
		else {
			name match {
				case AbcQName(name, namespace) => {
					if(name == 'void && namespace.name.name.length == 0) TaasVoidType
					else if(name == 'int && namespace.name.name.length == 0) TaasIntType
					else if(name == 'uint && namespace.name.name.length == 0) TaasLongType
					else if(name == 'Number && namespace.name.name.length == 0) TaasDoubleType
					else if(name == 'String && namespace.name.name.length == 0) TaasStringType
					else if(name == 'Boolean && namespace.name.name.length == 0) TaasBooleanType
					else if(name == 'Function && namespace.name.name.length == 0) TaasFunctionType
					else if(name == 'Object && namespace.name.name.length == 0) TaasObjectType
					else AbcTypes fromQName (name, namespace)
				}
				case AbcTypename(name, parameters) => AbcTypes fromTypename (name, parameters)
				case AbcMultiname(name, nsset) => AbcTypes fromQName (name, nsset.set(1))
				case _ => error("Unexpected name: " + name)
			}
		}
	}

	private def method2code(scope: Option[AbcNominalType], isStatic: Boolean, method: AbcMethod): Option[TaasCode] = {
		method.body match {
			case Some(body) => body.bytecode match {
				case Some(bytecode) => Some(new AbcCode(ast, abc, method, scope, isStatic))
				case None => None
			}
			case None => None
		}
	}
}