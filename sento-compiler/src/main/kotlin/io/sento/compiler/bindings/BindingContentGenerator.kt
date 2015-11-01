package io.sento.compiler.bindings

import io.sento.Bind
import io.sento.BindArray
import io.sento.BindBool
import io.sento.BindColor
import io.sento.BindDimen
import io.sento.BindDrawable
import io.sento.BindInteger
import io.sento.BindString
import io.sento.compiler.api.ContentGenerator
import io.sento.compiler.api.GeneratedContent
import io.sento.compiler.api.GenerationEnvironment
import io.sento.compiler.bindings.resources.BindArrayBindingGenerator
import io.sento.compiler.bindings.resources.BindBoolBindingGenerator
import io.sento.compiler.bindings.resources.BindColorBindingGenerator
import io.sento.compiler.bindings.resources.BindDimenBindingGenerator
import io.sento.compiler.bindings.resources.BindDrawableBindingGenerator
import io.sento.compiler.bindings.resources.BindIntegerBindingGenerator
import io.sento.compiler.bindings.resources.BindStringBindingGenerator
import io.sento.compiler.bindings.views.BindViewBindingGenerator
import io.sento.compiler.common.Types
import io.sento.compiler.specs.ClassSpec
import io.sento.compiler.specs.FieldSpec
import io.sento.compiler.specs.MethodSpec
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.objectweb.asm.Opcodes.*
import java.util.ArrayList
import java.util.HashMap

internal class BindingContentGenerator : ContentGenerator {
  private val generators = HashMap<Type, FieldBindingGenerator<out Annotation>>().apply {
    put(Type.getType(Bind::class.java), BindViewBindingGenerator())
    put(Type.getType(BindArray::class.java), BindArrayBindingGenerator())
    put(Type.getType(BindBool::class.java), BindBoolBindingGenerator())
    put(Type.getType(BindColor::class.java), BindColorBindingGenerator())
    put(Type.getType(BindDimen::class.java), BindDimenBindingGenerator())
    put(Type.getType(BindDrawable::class.java), BindDrawableBindingGenerator())
    put(Type.getType(BindInteger::class.java), BindIntegerBindingGenerator())
    put(Type.getType(BindString::class.java), BindStringBindingGenerator())
  }

  override fun onGenerateContent(clazz: ClassSpec, environment: GenerationEnvironment): List<GeneratedContent> {
    return ArrayList<GeneratedContent>().apply {
      if (shouldGenerateBindingClass(clazz, environment)) {
        add(GeneratedContent(onGenerateBindingClass(clazz, environment), clazz.generatedType.toClassFilePath()))
        add(GeneratedContent(onGenerateTargetClass(clazz, environment), clazz.originalType.toClassFilePath()))
      }
    }
  }

  private fun onGenerateBindingClass(clazz: ClassSpec, environment: GenerationEnvironment): ByteArray {
    return with (ClassWriter(0)) {
      visitHeader(clazz, environment)
      visitConstructor(clazz, environment)

      visitBindMethod(clazz, environment)
      visitUnbindMethod(clazz, environment)
      visitBindBridge(clazz, environment)
      visitUnbindBridge(clazz, environment)
      visitEnd()

      toByteArray()
    }
  }

  private fun onGenerateTargetClass(clazz: ClassSpec, environment: GenerationEnvironment): ByteArray {
    val writer = ClassWriter(0)

    clazz.toClassReader().accept(object : ClassVisitor(Opcodes.ASM5, writer) {
      override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
        return super.visitField(if (shouldGenerateBindingForField(clazz.field(name))) {
          access and ACC_PRIVATE.inv() and ACC_PROTECTED.inv() and ACC_FINAL.inv() or ACC_PUBLIC
        } else {
          access
        }, name, desc, signature, value)
      }

      override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
        return super.visitMethod(if (shouldGenerateBindingForMethod(clazz.method(name))) {
          access and ACC_PRIVATE.inv() and ACC_PROTECTED.inv() and ACC_FINAL.inv() or ACC_PUBLIC
        } else {
          access
        }, name, desc, signature, exceptions)
      }
    }, ClassReader.SKIP_FRAMES)

    return writer.toByteArray()
  }

  private fun shouldGenerateBindingClass(clazz: ClassSpec, environment: GenerationEnvironment): Boolean {
    return !Types.isSystemClass(clazz.type) && (clazz.fields.any {
      shouldGenerateBindingForField(it)
    } || clazz.methods.any {
      shouldGenerateBindingForMethod(it)
    })
  }

  private fun shouldGenerateBindingForField(field: FieldSpec?): Boolean {
    return field != null && field.annotations.any {
      generators.containsKey(it.type)
    }
  }

  private fun shouldGenerateBindingForMethod(method: MethodSpec?): Boolean {
    return method != null && method.annotations.any {
      generators.containsKey(it.type)
    }
  }

  private fun ClassWriter.visitHeader(clazz: ClassSpec, environment: GenerationEnvironment) = apply {
    val name = clazz.generatedType.internalName
    val signature = "<T:L${clazz.originalType.internalName};>L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_BINDING.internalName}<TT;>;"
    val superName = Types.TYPE_OBJECT.internalName
    val interfaces = arrayOf(Types.TYPE_BINDING.internalName)
    val source = clazz.generatedType.toSource()

    visit(Opcodes.V1_6, ACC_PUBLIC + ACC_SUPER, name, signature, superName, interfaces)
    visitSource(source, null)
  }

  private fun ClassWriter.visitConstructor(clazz: ClassSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)

    val start = Label()
    val end = Label()

    visitor.visitCode()
    visitor.visitLabel(start)
    visitor.visitVarInsn(ALOAD, 0)
    visitor.visitMethodInsn(INVOKESPECIAL, Types.TYPE_OBJECT.internalName, "<init>", "()V", false)
    visitor.visitInsn(RETURN)
    visitor.visitLabel(end)
    visitor.visitLocalVariable("this", clazz.generatedType.descriptor, "L${clazz.generatedType.internalName}<TT;>;", start, end, 0)
    visitor.visitMaxs(1, 1)
    visitor.visitEnd()
  }

  private fun ClassWriter.visitBindMethod(clazz: ClassSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC, "bind", "(L${clazz.originalType.internalName};L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_FINDER.internalName};)V", "<S:L${Types.TYPE_OBJECT.internalName};>(TT;TS;L${Types.TYPE_FINDER.internalName}<-TS;>;)V", null)

    val start = Label()
    val end = Label()

    visitor.visitCode()
    visitor.visitLabel(start)

    clazz.fields.forEach { field ->
      field.annotations.forEach { annotation ->
        val generator = generators.get(annotation.type)
        val value = annotation.resolve<Annotation>()

        if (generator != null) {
          val variables = mapOf("this" to 0, "target" to 1, "source" to 2, "finder" to 3)
          val context = FieldBindingContext(field, clazz, value, visitor, variables)

          generator.bind(context, environment)
        }
      }
    }

    visitor.visitInsn(RETURN)
    visitor.visitLabel(end)

    visitor.visitLocalVariable("this", clazz.generatedType.descriptor, "L${clazz.generatedType.internalName}<TT;>;", start, end, 0)
    visitor.visitLocalVariable("target", clazz.originalType.descriptor, "TT;", start, end, 1)
    visitor.visitLocalVariable("source", Types.TYPE_OBJECT.descriptor, "TS;", start, end, 2)
    visitor.visitLocalVariable("finder", Types.TYPE_FINDER.descriptor, "L${Types.TYPE_FINDER.internalName}<-TS;>;", start, end, 3)

    visitor.visitMaxs(5, 4)
    visitor.visitEnd()
  }

  private fun ClassWriter.visitUnbindMethod(clazz: ClassSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC, "unbind", "(L${clazz.originalType.internalName};)V", "(TT;)V", null)

    val start = Label()
    val end = Label()

    visitor.visitCode()
    visitor.visitLabel(start)

    clazz.fields.forEach { field ->
      field.annotations.forEach { annotation ->
        val generator = generators.get(annotation.type)
        val value = annotation.resolve<Annotation>()

        if (generator != null) {
          val variables = mapOf("this" to 0, "target" to 1)
          val context = FieldBindingContext(field, clazz, value, visitor, variables)

          generator.unbind(context, environment)
        }
      }
    }

    visitor.visitInsn(RETURN)
    visitor.visitLabel(end)
    visitor.visitLocalVariable("this", clazz.generatedType.descriptor, "L${clazz.generatedType.internalName}<TT;>;", start, end, 0)
    visitor.visitLocalVariable("target", clazz.originalType.descriptor, "TT;", start, end, 1)
    visitor.visitMaxs(2, 2)
    visitor.visitEnd()
  }

  private fun ClassWriter.visitBindBridge(clazz: ClassSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "bind", "(L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_FINDER.internalName};)V", null, null)

    val start = Label()
    val end = Label()

    visitor.visitCode()
    visitor.visitLabel(start)
    visitor.visitVarInsn(ALOAD, 0)
    visitor.visitVarInsn(ALOAD, 1)
    visitor.visitTypeInsn(CHECKCAST, clazz.originalType.internalName)
    visitor.visitVarInsn(ALOAD, 2)
    visitor.visitVarInsn(ALOAD, 3)
    visitor.visitMethodInsn(INVOKEVIRTUAL, clazz.generatedType.internalName, "bind", "(L${clazz.originalType.internalName};L${Types.TYPE_OBJECT.internalName};L${Types.TYPE_FINDER.internalName};)V", false)
    visitor.visitInsn(RETURN)
    visitor.visitLabel(end)
    visitor.visitLocalVariable("this", clazz.generatedType.descriptor, "L${clazz.generatedType.internalName}<TT;>;", start, end, 0)
    visitor.visitMaxs(4, 4)
    visitor.visitEnd()
  }

  private fun ClassWriter.visitUnbindBridge(clazz: ClassSpec, environment: GenerationEnvironment) {
    val visitor = visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "unbind", "(L${Types.TYPE_OBJECT.internalName};)V", null, null)

    val start = Label()
    val end = Label()

    visitor.visitCode()
    visitor.visitLabel(start)
    visitor.visitVarInsn(ALOAD, 0)
    visitor.visitVarInsn(ALOAD, 1)
    visitor.visitTypeInsn(CHECKCAST, clazz.originalType.internalName)
    visitor.visitMethodInsn(INVOKEVIRTUAL, clazz.generatedType.internalName, "unbind", "(L${clazz.originalType.internalName};)V", false)
    visitor.visitInsn(RETURN)
    visitor.visitLabel(end)
    visitor.visitLocalVariable("this", clazz.generatedType.descriptor, "L${clazz.generatedType.internalName}<TT;>;", start, end, 0)
    visitor.visitMaxs(2, 2)
    visitor.visitEnd()
  }

  private fun Type.toSource(): String {
    val className = className

    return if (className.contains('.')) {
      "${className.substring(className.lastIndexOf('.') + 1)}.java"
    } else {
      "$className.java"
    }
  }

  private fun Type.toJavaFilePath(): String {
    return "$internalName.java"
  }

  private fun Type.toClassFilePath(): String {
    return "$internalName.class"
  }

  private val ClassSpec.generatedType: Type
    get() = Type.getObjectType("${type.internalName}\$\$SentoBinding")

  private val ClassSpec.originalType: Type
    get() = type
}
