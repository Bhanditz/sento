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
import io.sento.compiler.common.body
import io.sento.compiler.common.isStatic
import io.sento.compiler.common.isSynthetic
import io.sento.compiler.model.FieldTargetSpec
import io.sento.compiler.model.MethodTargetSpec
import io.sento.compiler.model.SentoBindingSpec
import io.sento.compiler.model.ViewTargetSpec
import io.sento.compiler.reflection.ClassSpec
import io.sento.compiler.reflection.FieldSpec
import io.sento.compiler.reflection.MethodSpec
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PROTECTED
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ASM5
import org.objectweb.asm.Opcodes.V1_6
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.slf4j.LoggerFactory
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

  private val logger = LoggerFactory.getLogger(SentoBindingContentGenerator::class.java)
  private val optional = OptionalAware(clazz)

  private val binding by lazy(LazyThreadSafetyMode.NONE) {
    SentoBindingSpec.create(clazz)
  }

  private val bindableFieldTargets by lazy(LazyThreadSafetyMode.NONE) {
    binding.clazz.fields.flatMap { field ->
      field.annotations.map { annotation ->
        fields[annotation.type]?.let { generator ->
          FieldTargetSpec(field, annotation, generator, optional.isOptional(field))
        }
      }.filterNotNull()
    }
  }

  private val bindableMethodTargets by lazy(LazyThreadSafetyMode.NONE) {
    binding.clazz.methods.flatMap { method ->
      method.annotations.map { annotation ->
        methods[annotation.type]?.let {
          MethodTargetSpec(method, annotation, it, optional.isOptional(method))
        }
      }.filterNotNull()
    }
  }

  private val bindableViewTargetsForFields by lazy(LazyThreadSafetyMode.NONE) {
    bindableFieldTargets.flatMap { field ->
      field.annotation.ids.map { id ->
        ViewTargetSpec(id, field.optional, "field '${field.field.name}'")
      }
    }
  }

  private val bindableViewTargetsForMethods by lazy(LazyThreadSafetyMode.NONE) {
    bindableMethodTargets.flatMap { method ->
      method.annotation.ids.map { id ->
        ViewTargetSpec(id, method.optional, "method '${method.method.name}'")
      }
    }
  }

  private val bindableViewTargetsForAll by lazy(LazyThreadSafetyMode.NONE) {
    bindableViewTargetsForFields + bindableViewTargetsForMethods
  }

  override fun generate(environment: GenerationEnvironment): Collection<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      if (shouldGenerateBindingClass(clazz, environment)) {
        logger.info("Generating SentoBinding for '{}' class:", clazz.type.className)

        val bytes = environment.createClass {
          visitHeader(environment)
          visitConstructor(environment)

          visitBindMethod(environment).apply {
            addAll(this)
          }

          visitUnbindMethod(environment).apply {
            addAll(this)
          }
        }

        add(GeneratedContent(Types.getClassFilePath(binding.originalType), AccessibilityPatcher(environment).patch(clazz)))
        add(GeneratedContent(Types.getClassFilePath(binding.generatedType), bytes, HashMap<String, Any>().apply {
          put(EXTRA_BINDING_SPEC, binding)
        }))
      }
    }
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

  private fun ClassWriter.visitHeader(environment: GenerationEnvironment) = apply {
    val name = binding.generatedType.internalName
    val signature = "L${Types.OBJECT.internalName};L${Types.BINDING.internalName}<L${Types.OBJECT.internalName};>;"
    val superName = Types.OBJECT.internalName
    val interfaces = arrayOf(Types.BINDING.internalName)

    visit(V1_6, ACC_PUBLIC + ACC_SUPER, name, signature, superName, interfaces)
  }

  private fun ClassWriter.visitConstructor(environment: GenerationEnvironment) {
    GeneratorAdapter(ACC_PUBLIC, Methods.getConstructor(), null, null, this).body {
      loadThis()
      invokeConstructor(Types.OBJECT, Methods.getConstructor())
    }
  }

  private fun ClassWriter.visitBindMethod(environment: GenerationEnvironment): List<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      val descriptor = Methods.get("bind", Types.VOID, Types.OBJECT, Types.OBJECT, Types.FINDER)
      val signature = "<S:Ljava/lang/Object;>(Ljava/lang/Object;TS;Lio/sento/Finder<-TS;>;)V"

      GeneratorAdapter(ACC_PUBLIC, descriptor, signature, null, this@visitBindMethod).body {
        val arguments = mapOf("target" to 0, "source" to 1, "finder" to 2)
        val variables = HashMap<String, Int>()

        variables.put("target", newLocal(binding.originalType).apply {
          loadArg(arguments["target"]!!)
          checkCast(binding.originalType)
          storeLocal(this)
        })

        bindableViewTargetsForAll.distinctBy { it.id }.forEach {
          variables.put("view${it.id}", newLocal(Types.VIEW).apply {
            loadArg(arguments["finder"]!!)
            push(it.id)

            loadArg(arguments["source"]!!)
            invokeInterface(Types.FINDER, Methods.get("find", Types.VIEW, Types.INT, Types.OBJECT))

            storeLocal(this)
          })
        }

        bindableViewTargetsForAll.filter { !it.optional }.distinctBy { it.id }.forEach {
          loadArg(arguments["finder"]!!)
          push(it.id)

          loadLocal(variables["view${it.id}"]!!)
          loadArg(arguments["source"]!!)
          push(it.owner)

          invokeInterface(Types.FINDER, Methods.get("require", Types.VOID, Types.INT, Types.VIEW, Types.OBJECT, Types.STRING))
        }

        bindableViewTargetsForMethods.distinctBy { it.id }.forEach {
          loadLocal(variables["target"]!!)
          loadLocal(variables["view${it.id}"]!!)
          putField(binding.clazz.type, cachedFieldNameForViewTarget(it), Types.VIEW)
        }

        bindableFieldTargets.forEach {
          addAll(it.generator.bind(FieldBindingContext(it.field, binding.clazz, it.annotation, this,
              variables, arguments, binding.factory, it.optional), environment))
        }

        bindableMethodTargets.forEach {
          addAll(it.generator.bind(MethodBindingContext(it.method, binding.clazz, it.annotation, this,
              variables, arguments, binding.factory, it.optional), environment))
        }
      }
    }
  }

  private fun ClassWriter.visitUnbindMethod(environment: GenerationEnvironment): List<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      GeneratorAdapter(ACC_PUBLIC, Methods.get("unbind", Types.VOID, Types.OBJECT), null, null, this@visitUnbindMethod).body {
        val arguments = mapOf("target" to 0)
        
        val variables = mapOf("target" to newLocal(binding.originalType).apply {
          loadArg(arguments["target"]!!)
          checkCast(binding.originalType)
          storeLocal(this)
        })

        bindableFieldTargets.forEach {
          addAll(it.generator.unbind(FieldBindingContext(it.field, binding.clazz, it.annotation, this,
              variables, arguments, binding.factory, it.optional), environment))
        }

        bindableMethodTargets.forEach {
          addAll(it.generator.unbind(MethodBindingContext(it.method, binding.clazz, it.annotation, this,
              variables, arguments, binding.factory, it.optional), environment))
        }

        bindableViewTargetsForMethods.distinctBy { it.id }.forEach {
          loadLocal(variables["target"]!!)
          visitInsn(Opcodes.ACONST_NULL)
          putField(binding.clazz.type, cachedFieldNameForViewTarget(it), Types.VIEW)
        }
      }
    }
  }

  private fun cachedFieldNameForViewTarget(target: ViewTargetSpec): String {
    return "sento\$cached\$view_${target.id}"
  }

  private inner class AccessibilityPatcher(val environment: GenerationEnvironment) {
    public fun patch(spec: ClassSpec): ByteArray {
      val bytes = spec.opener.open()
      val reader = ClassReader(bytes)

      val writer = object : ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS) {
        override fun getCommonSuperClass(left: String, right: String): String {
          return Types.OBJECT.internalName
        }
      }

      reader.accept(object : ClassVisitor(ASM5, writer) {
        override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
          return super.visitField(if (shouldGenerateBindingForField(clazz.getDeclaredField(name), environment)) {
            access and ACC_PRIVATE.inv() and ACC_FINAL.inv() or ACC_PROTECTED
          } else {
            access
          }, name, desc, signature, value)
        }
      }, ClassReader.SKIP_FRAMES)

      bindableViewTargetsForMethods.distinctBy { it.id }.forEach {
        writer.visitField(ACC_PROTECTED + ACC_SYNTHETIC, cachedFieldNameForViewTarget(it), Types.VIEW.descriptor, null, null)
      }

      bindableMethodTargets.forEach {
        GeneratorAdapter(ACC_PUBLIC + ACC_STATIC + ACC_SYNTHETIC, Methods.getAccessor(spec.type, it.method), null, null, writer).body {
          for (count in 0..it.method.arguments.size) {
            loadArg(count)
          }
          invokeVirtual(spec.type, Methods.get(it.method))
        }
      }

      return writer.toByteArray()
    }
  }
}
