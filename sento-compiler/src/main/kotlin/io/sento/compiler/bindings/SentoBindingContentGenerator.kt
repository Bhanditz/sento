package io.sento.compiler.bindings

import io.sento.compiler.ContentGenerator
import io.sento.compiler.GeneratedContent
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.bindings.fields.FieldBindingContext
import io.sento.compiler.bindings.fields.FieldBindingGenerator
import io.sento.compiler.bindings.methods.MethodBindingContext
import io.sento.compiler.bindings.methods.MethodBindingGenerator
import io.sento.compiler.common.Types
import io.sento.compiler.common.toClassFilePath
import io.sento.compiler.common.toSourceFilePath
import io.sento.compiler.model.SentoBindingSpec
import io.sento.compiler.model.ClassSpec
import io.sento.compiler.model.FieldSpec
import io.sento.compiler.model.MethodSpec
import io.sento.compiler.patcher.AccessibilityPatcher
import io.sento.compiler.patcher.ClassPatcher
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.objectweb.asm.Opcodes.*
import java.util.ArrayList
import java.util.HashMap

internal class SentoBindingContentGenerator(
    private val fields: Map<Type, FieldBindingGenerator>,
    private val methods: Map<Type, MethodBindingGenerator>,
    private val clazz: ClassSpec
) : ContentGenerator {
  public companion object {
    public const val EXTRA_BINDING_SPEC = "EXTRA_BINDING_SPEC"
  }

  override fun generate(environment: GenerationEnvironment): Collection<GeneratedContent> {
    if (!shouldGenerateBindingClass(clazz, environment)) {
      return emptyList()
    }

    val binding = SentoBindingSpec.from(clazz)
    val result = ArrayList<GeneratedContent>()

    val bytes = environment.createClass {
      visitHeader(binding, environment)
      visitConstructor(binding, environment)

      visitBindMethod(binding, environment).apply {
        result.addAll(this)
      }

      visitUnbindMethod(binding, environment).apply {
        result.addAll(this)
      }
    }

    result.add(GeneratedContent(binding.originalType.toClassFilePath(), onGenerateTargetClass(clazz, environment)))
    result.add(GeneratedContent(binding.generatedType.toClassFilePath(), bytes, HashMap<String, Any>().apply {
      put(EXTRA_BINDING_SPEC, binding)
    }))

    return result
  }

  private fun onGenerateTargetClass(clazz: ClassSpec, environment: GenerationEnvironment): ByteArray {
    return createAccessibilityPatcher(environment).patch(clazz.opener.open())
  }

  private fun shouldGenerateBindingClass(clazz: ClassSpec, environment: GenerationEnvironment): Boolean {
    return !Types.isSystemClass(clazz.type) && (clazz.fields.any {
      shouldGenerateBindingForField(it, environment)
    } || clazz.methods.any {
      shouldGenerateBindingForMethod(it, environment)
    })
  }

  private fun shouldGenerateBindingForField(field: FieldSpec?, environment: GenerationEnvironment): Boolean {
    return field != null && field.annotations.any {
      fields.containsKey(it.type)
    }
  }

  private fun shouldGenerateBindingForMethod(method: MethodSpec?, environment: GenerationEnvironment): Boolean {
    return method != null && method.annotations.any {
      methods.containsKey(it.type)
    }
  }

  private fun createAccessibilityPatcher(environment: GenerationEnvironment): ClassPatcher {
    return object : AccessibilityPatcher() {
      override fun onPatchFieldFlags(access: Int, name: String, desc: String, signature: String?, value: Any?): Int {
        return if (shouldGenerateBindingForField(clazz.field(name), environment)) {
          access and ACC_PRIVATE.inv() and ACC_PROTECTED.inv() and ACC_FINAL.inv() or ACC_PUBLIC
        } else {
          access
        }
      }

      override fun onPatchMethodFlags(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): Int {
        return if (shouldGenerateBindingForMethod(clazz.method(name), environment)) {
          access and ACC_PRIVATE.inv() and ACC_PROTECTED.inv() and ACC_FINAL.inv() or ACC_PUBLIC
        } else {
          access
        }
      }
    }
  }

  private fun ClassWriter.visitHeader(binding: SentoBindingSpec, environment: GenerationEnvironment) = apply {
    val name = binding.generatedType.internalName
    val signature = "L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_BINDING.internalName}<L${Types.TYPE_OBJECT.internalName};>;"
    val superName = Types.TYPE_OBJECT.internalName
    val interfaces = arrayOf(Types.TYPE_BINDING.internalName)
    val source = binding.generatedType.toSourceFilePath()

    visit(Opcodes.V1_6, ACC_PUBLIC + ACC_SUPER, name, signature, superName, interfaces)
    visitSource(source, null)
  }

  private fun ClassWriter.visitConstructor(binding: SentoBindingSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)

    visitor.visitCode()
    visitor.visitVarInsn(ALOAD, 0)
    visitor.visitMethodInsn(INVOKESPECIAL, Types.TYPE_OBJECT.internalName, "<init>", "()V", false)
    visitor.visitInsn(RETURN)
    visitor.visitMaxs(1, 1)
    visitor.visitEnd()
  }

  private fun ClassWriter.visitBindMethod(binding: SentoBindingSpec, environment: GenerationEnvironment): List<GeneratedContent> {
    val descriptor = "(L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_FINDER.internalName};)V"
    val signature = "<S:L${Types.TYPE_OBJECT.internalName};>(L${Types.TYPE_OBJECT.internalName};TS;L${Types.TYPE_FINDER.internalName}<-TS;>;)V"

    val visitor = visitMethod(ACC_PUBLIC, "bind", descriptor, signature, null)
    val result = ArrayList<GeneratedContent>()

    visitor.visitCode()

    for (field in binding.clazz.fields) {
      for (annotation in field.annotations) {
        fields[annotation.type]?.let {
          val variables = mapOf("this" to 0, "target" to 1, "source" to 2, "finder" to 3)
          val context = FieldBindingContext(field, binding.clazz, annotation, visitor, variables, binding.factory, environment)

          result.addAll(it.bind(context, environment))
        }
      }
    }

    for (method in binding.clazz.methods) {
      for (annotation in method.annotations) {
        methods[annotation.type]?.let {
          val variables = mapOf("this" to 0, "target" to 1, "source" to 2, "finder" to 3)
          val context = MethodBindingContext(method, binding.clazz, annotation, visitor, variables, binding.factory, environment)

          result.addAll(it.bind(context, environment))
        }
      }
    }

    visitor.visitInsn(RETURN)
    visitor.visitMaxs(5, 4)
    visitor.visitEnd()

    return result
  }

  private fun ClassWriter.visitUnbindMethod(binding: SentoBindingSpec, environment: GenerationEnvironment): List<GeneratedContent> {
    val visitor = visitMethod(ACC_PUBLIC, "unbind", "(L${Types.TYPE_OBJECT.internalName};)V", null, null)
    val result = ArrayList<GeneratedContent>()

    visitor.visitCode()

    for (field in binding.clazz.fields) {
      for (annotation in field.annotations) {
        fields[annotation.type]?.let {
          val variables = mapOf("this" to 0, "target" to 1)
          val context = FieldBindingContext(field, binding.clazz, annotation, visitor, variables, binding.factory, environment)

          result.addAll(it.unbind(context, environment))
        }
      }
    }

    for (method in binding.clazz.methods) {
      for (annotation in method.annotations) {
        methods[annotation.type]?.let {
          val variables = mapOf("this" to 0, "target" to 1)
          val context = MethodBindingContext(method, binding.clazz, annotation, visitor, variables, binding.factory, environment)

          result.addAll(it.unbind(context, environment))
        }
      }
    }

    visitor.visitInsn(RETURN)
    visitor.visitMaxs(2, 2)
    visitor.visitEnd()

    return result
  }
}
