package io.sento.compiler.bindings.resources

import io.sento.BindInteger
import io.sento.compiler.bindings.FieldBindingContext
import io.sento.compiler.api.GenerationEnvironment
import io.sento.compiler.bindings.SimpleFieldBindingGenerator
import io.sento.compiler.common.Types
import org.objectweb.asm.Opcodes

internal class BindIntegerBindingGenerator : SimpleFieldBindingGenerator<BindInteger>() {
  override fun onBind(context: FieldBindingContext<BindInteger>, environment: GenerationEnvironment) {
    val visitor = context.visitor
    val annotation = context.annotation

    val field = context.field
    val clazz = context.clazz

    visitor.visitVarInsn(Opcodes.ALOAD, context.variable("target"))
    visitor.visitVarInsn(Opcodes.ALOAD, context.variable("finder"))
    visitor.visitVarInsn(Opcodes.ALOAD, context.variable("source"))

    visitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Types.TYPE_FINDER.internalName, "resources", "(L${Types.TYPE_OBJECT.internalName};)L${Types.TYPE_RESOURCES.internalName};", true)
    visitor.visitLdcInsn(annotation.value)

    visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Types.TYPE_RESOURCES.internalName, "getInteger", "(I)I", false)
    visitor.visitFieldInsn(Opcodes.PUTFIELD, clazz.type.internalName, field.name, field.type.descriptor)
  }
}
