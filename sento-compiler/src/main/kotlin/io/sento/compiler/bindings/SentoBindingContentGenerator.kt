package io.sento.compiler.bindings

import io.sento.compiler.ContentGenerator
import io.sento.compiler.GeneratedContent
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.annotations.ids
import io.sento.compiler.bindings.fields.FieldBindingContext
import io.sento.compiler.bindings.fields.FieldBindingGenerator
import io.sento.compiler.bindings.methods.MethodBindingContext
import io.sento.compiler.bindings.methods.MethodBindingGenerator
import io.sento.compiler.common.Methods
import io.sento.compiler.common.OptionalAware
import io.sento.compiler.common.Types
import io.sento.compiler.common.isPublic
import io.sento.compiler.common.isStatic
import io.sento.compiler.common.isSynthetic
import io.sento.compiler.model.ClassSpec
import io.sento.compiler.model.FieldSpec
import io.sento.compiler.model.MethodSpec
import io.sento.compiler.model.SentoBindingSpec
import io.sento.compiler.patcher.ClassPatcher
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.V1_6
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.slf4j.LoggerFactory
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashSet

internal class SentoBindingContentGenerator(
    private val fields: Map<Type, FieldBindingGenerator>,
    private val methods: Map<Type, MethodBindingGenerator>,
    private val clazz: ClassSpec
) : ContentGenerator {
  private val logger = LoggerFactory.getLogger(SentoBindingContentGenerator::class.java)
  private val optional = OptionalAware(clazz)

  public companion object {
    public const val EXTRA_BINDING_SPEC = "EXTRA_BINDING_SPEC"
  }

  override fun generate(environment: GenerationEnvironment): Collection<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      if (shouldGenerateBindingClass(clazz, environment)) {
        logger.info("Generating SentoBinding for '{}' class:", clazz.type.className)

        val binding = SentoBindingSpec.create(clazz)
        val bytes = environment.createClass {
          visitHeader(binding, environment)
          visitConstructor(binding, environment)

          visitBindMethod(binding, environment).apply {
            addAll(this)
          }

          visitUnbindMethod(binding, environment).apply {
            addAll(this)
          }
        }

        add(GeneratedContent(Types.getClassFilePath(binding.originalType), onGenerateTargetClass(clazz, environment)))
        add(GeneratedContent(Types.getClassFilePath(binding.generatedType), bytes, HashMap<String, Any>().apply {
          put(EXTRA_BINDING_SPEC, binding)
        }))
      }
    }
  }

  private fun onGenerateTargetClass(clazz: ClassSpec, environment: GenerationEnvironment): ByteArray {
    return AccessibilityPatcher(environment).patch(clazz)
  }

  private fun shouldGenerateBindingClass(clazz: ClassSpec, environment: GenerationEnvironment): Boolean {
    return !Types.isSystemClass(clazz.type) && (clazz.fields.any {
      shouldGenerateBindingForField(it, environment)
    } || clazz.methods.any {
      shouldGenerateBindingForMethod(it, environment)
    })
  }

  private fun shouldGenerateBindingForField(field: FieldSpec?, environment: GenerationEnvironment): Boolean {
    return field != null && !field.access.isStatic && !field.access.isSynthetic && field.annotations.any {
      fields.containsKey(it.type)
    }
  }

  private fun shouldGenerateBindingForMethod(method: MethodSpec?, environment: GenerationEnvironment): Boolean {
    return method != null && !method.access.isStatic && !method.access.isSynthetic && method.annotations.any {
      methods.containsKey(it.type)
    }
  }

  private fun ClassWriter.visitHeader(binding: SentoBindingSpec, environment: GenerationEnvironment) = apply {
    val name = binding.generatedType.internalName
    val signature = "L${Types.OBJECT.internalName};L${Types.BINDING.internalName}<L${Types.OBJECT.internalName};>;"
    val superName = Types.OBJECT.internalName
    val interfaces = arrayOf(Types.BINDING.internalName)

    visit(V1_6, ACC_PUBLIC + ACC_SUPER, name, signature, superName, interfaces)
  }

  private fun ClassWriter.visitConstructor(binding: SentoBindingSpec, environment: GenerationEnvironment) {
    GeneratorAdapter(ACC_PUBLIC, Methods.getConstructor(), null, null, this).apply {
      loadThis()
      invokeConstructor(Types.OBJECT, Methods.getConstructor())

      returnValue()
      endMethod()
    }
  }

  private fun ClassWriter.visitBindMethod(binding: SentoBindingSpec, environment: GenerationEnvironment): List<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      val descriptor = Methods.get("bind", Types.VOID, Types.OBJECT, Types.OBJECT, Types.FINDER)
      val signature = "<S:Ljava/lang/Object;>(Ljava/lang/Object;TS;Lio/sento/Finder<-TS;>;)V"

      GeneratorAdapter(ACC_PUBLIC, descriptor, signature, null, this@visitBindMethod).apply {
        val arguments = mapOf("target" to 0, "source" to 1, "finder" to 2)
        val variables = HashMap<String, Int>()

        variables.put("target", newLocal(binding.originalType).apply {
          loadArg(arguments["target"]!!)
          checkCast(binding.originalType)
          storeLocal(this)
        })

        findBindableViewIds(binding).forEach {
          if (!variables.containsKey("view$it")) {
            variables.put("view$it", newLocal(Types.VIEW).apply {
              loadArg(arguments["finder"]!!)
              push(it)

              loadArg(arguments["source"]!!)
              invokeInterface(Types.FINDER, Methods.get("find", Types.VIEW, Types.INT, Types.OBJECT))

              storeLocal(this)
            })
          }
        }

        for (field in binding.clazz.fields) {
          for (annotation in field.annotations) {
            fields[annotation.type]?.let {
              val optional = optional.isOptional(field)
              val context = FieldBindingContext(field, binding.clazz, annotation, this, variables, arguments, binding.factory, optional)

              addAll(it.bind(context, environment))
            }
          }
        }

        for (method in binding.clazz.methods) {
          for (annotation in method.annotations) {
            methods[annotation.type]?.let {
              val optional = optional.isOptional(method)
              val context = MethodBindingContext(method, binding.clazz, annotation, this, variables, arguments, binding.factory, optional)

              addAll(it.bind(context, environment))
            }
          }
        }

        returnValue()
        endMethod()
      }
    }
  }

  private fun ClassWriter.visitUnbindMethod(binding: SentoBindingSpec, environment: GenerationEnvironment): List<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      GeneratorAdapter(ACC_PUBLIC, Methods.get("unbind", Types.VOID, Types.OBJECT), null, null, this@visitUnbindMethod).apply {
        val arguments = mapOf("target" to 0)
        val variables = mapOf("target" to newLocal(binding.originalType).apply {
          loadArg(arguments["target"]!!)
          checkCast(binding.originalType)
          storeLocal(this)
        })

        for (field in binding.clazz.fields) {
          for (annotation in field.annotations) {
            fields[annotation.type]?.let {
              val optional = optional.isOptional(field)
              val context = FieldBindingContext(field, binding.clazz, annotation, this, variables, arguments, binding.factory, optional)

              addAll(it.unbind(context, environment))
            }
          }
        }

        for (method in binding.clazz.methods) {
          for (annotation in method.annotations) {
            methods[annotation.type]?.let {
              val optional = optional.isOptional(method)
              val context = MethodBindingContext(method, binding.clazz, annotation, this, variables, arguments, binding.factory, optional)

              addAll(it.unbind(context, environment))
            }
          }
        }

        returnValue()
        endMethod()
      }
    }
  }

  private fun findBindableViewIds(binding: SentoBindingSpec): Collection<Int> {
    return LinkedHashSet<Int>().apply {
      for (field in binding.clazz.fields) {
        for (annotation in field.annotations) {
          if (fields[annotation.type] != null) {
            addAll(annotation.ids.toArrayList())
          }
        }
      }

      for (method in binding.clazz.methods) {
        for (annotation in method.annotations) {
          if (methods[annotation.type] != null) {
            addAll(annotation.ids.toArrayList())
          }
        }
      }
    }
  }

  private inner class AccessibilityPatcher(val environment: GenerationEnvironment) : ClassPatcher {
    override fun patch(spec: ClassSpec): ByteArray {
      val bytes = spec.opener.open()
      val reader = ClassReader(bytes)

      val writer = object : ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS) {
        override fun getCommonSuperClass(left: String, right: String): String {
          return Types.OBJECT.internalName
        }
      }

      reader.accept(object : ClassVisitor(Opcodes.ASM5, writer) {
        override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
          return super.visitField(onPatchFieldFlags(access, name), name, desc, signature, value)
        }
      }, ClassReader.SKIP_FRAMES)

      spec.methods.forEach {
        if (!it.access.isPublic && shouldGenerateBindingForMethod(it, environment)) {
          GeneratorAdapter(ACC_PUBLIC + ACC_STATIC + ACC_SYNTHETIC, Methods.getAccessor(spec.type, it), null, null, writer).apply {
            for (count in 0..it.arguments.size) {
              loadArg(count)
            }

            invokeVirtual(spec.type, Methods.get(it)).apply {
              returnValue()
              endMethod()
            }
          }
        }
      }

      return writer.toByteArray()
    }

    private fun onPatchFieldFlags(access: Int, name: String): Int {
      return if (shouldGenerateBindingForField(clazz.getDeclaredField(name), environment)) {
        access and Opcodes.ACC_PRIVATE.inv() and Opcodes.ACC_PROTECTED.inv() and Opcodes.ACC_FINAL.inv() or ACC_PUBLIC
      } else {
        access
      }
    }
  }
}
