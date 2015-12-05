package io.sento.compiler.bindings.methods

import io.sento.compiler.GeneratedContent
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.SentoException
import io.sento.compiler.annotations.ids
import io.sento.compiler.common.Methods
import io.sento.compiler.common.Naming
import io.sento.compiler.common.Types
import io.sento.compiler.common.body
import io.sento.compiler.common.isAbstract
import io.sento.compiler.common.isInterface
import io.sento.compiler.common.isPrivate
import io.sento.compiler.common.simpleName
import io.sento.compiler.model.ListenerClassSpec
import io.sento.compiler.reflection.MethodSpec
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.V1_6
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.slf4j.LoggerFactory
import java.util.ArrayList
import java.util.LinkedHashSet

internal class ListenerBindingGenerator (
    public val spec: ListenerClassSpec
) : MethodBindingGenerator {
  private val logger = LoggerFactory.getLogger(ListenerBindingGenerator::class.java)

  override fun bind(context: MethodBindingContext, environment: GenerationEnvironment): List<GeneratedContent> {
    logger.info("Generating @{} binding for '{}' method",
        context.annotation.type.simpleName, context.method.name)

    val listener = createListenerSpec(context, environment)
    val result = listOf(onCreateBindingListener(listener, environment))
    val adapter = context.adapter

    context.annotation.ids.forEach {
      adapter.newLabel().apply {
        if (context.optional) {
          adapter.loadLocal(context.variable("view$it"))
          adapter.ifNull(this)
        }

        adapter.loadLocal(context.variable("view$it")).apply {
          if (spec.owner.type != Types.VIEW) {
            adapter.checkCast(spec.owner.type)
          }
        }

        adapter.newInstance(listener.type)
        adapter.dup()

        adapter.loadLocal(context.variable("target"))
        adapter.invokeConstructor(listener.type, Methods.getConstructor(listener.target))
        adapter.invokeVirtual(spec.owner.type, Methods.get(spec.setter))

        adapter.mark(this)
      }
    }

    return result
  }

  private fun onCreateBindingListener(listener: ListenerSpec, environment: GenerationEnvironment): GeneratedContent {
    return GeneratedContent(Types.getClassFilePath(listener.type), environment.newClass {
      visitListenerHeader(listener, environment)
      visitListenerFields(listener, environment)
      visitListenerConstructor(listener, environment)

      val registry = environment.registry
      val callbacks = registry.listPublicMethods(spec.listener).filter {
        it.access.isAbstract
      }

      callbacks.forEach {
        if (it.name == listener.callback.name && it.type == listener.callback.type) {
          visitListenerCallback(listener, environment)
        } else {
          visitListenerStub(listener, it, environment)
        }
      }
    })
  }

  private fun ClassVisitor.visitListenerHeader(listener: ListenerSpec, environment: GenerationEnvironment) {
    visit(V1_6, ACC_PUBLIC + ACC_SUPER, listener.type.internalName, null, spec.listenerParent.internalName, spec.listenerInterfaces.map { it.internalName }.toTypedArray())
  }

  private fun ClassVisitor.visitListenerFields(listener: ListenerSpec, environment: GenerationEnvironment) {
    visitField(ACC_PRIVATE + ACC_FINAL, "target", listener.target.descriptor, null, null)
  }

  private fun ClassVisitor.visitListenerConstructor(listener: ListenerSpec, environment: GenerationEnvironment) {
    GeneratorAdapter(ACC_PUBLIC, Methods.getConstructor(listener.target), null, null, this).body {
      loadThis()
      invokeConstructor(spec.listenerParent, Methods.getConstructor())

      loadThis()
      loadArg(0)
      putField(listener.type, "target", listener.target)
    }
  }

  private fun ClassVisitor.visitListenerCallback(listener: ListenerSpec, environment: GenerationEnvironment) {
    GeneratorAdapter(ACC_PUBLIC, Methods.get(listener.callback), listener.callback.signature, null, this).body {
      loadThis()
      getField(listener.type, "target", listener.target)

      listener.args.forEach {
        loadArg(it.index).apply {
          if (!Types.isPrimitive(it.type)) {
            checkCast(it.type)
          }
        }
      }

      if (listener.method.access.isPrivate) {
        invokeStatic(listener.target, Naming.getSyntheticAccessor(listener.target, listener.method))
      } else {
        invokeVirtual(listener.target, Methods.get(listener.method))
      }

      if (listener.callback.returns == Types.BOOLEAN && listener.method.returns == Types.VOID) {
        push(false)
      }
    }
  }

  private fun ClassVisitor.visitListenerStub(listener: ListenerSpec, method: MethodSpec, environment: GenerationEnvironment) {
    GeneratorAdapter(ACC_PUBLIC, Methods.get(method), method.signature, null, this).body {
      if (method.returns == Types.BOOLEAN) {
        push(false)
      }
    }
  }

  private fun createListenerSpec(context: MethodBindingContext, environment: GenerationEnvironment): ListenerSpec {
    val type = Naming.getAnonymousType(Naming.getSentoBindingType(context.clazz.type))
    val args = remapMethodArgs(context, environment)

    if (context.method.returns !in listOf(Types.VOID, Types.BOOLEAN)) {
      throw SentoException("Unable to generate @{0} binding for ''{1}#{2}'' method - it returns ''{3}'', but only {4} are supported.",
          context.annotation.type.simpleName, context.clazz.type.className, context.method.name, context.method.returns.className, listOf(Types.VOID.className, Types.BOOLEAN.className))
    }

    return ListenerSpec(type, context.clazz.type, spec.callback, context.method, args)
  }

  private fun remapMethodArgs(context: MethodBindingContext, environment: GenerationEnvironment): Collection<ArgumentSpec> {
    val result = ArrayList<ArgumentSpec>()
    val available = LinkedHashSet<Int>()

    val from = spec.callback
    val to = context.method

    for (index in 0..from.arguments.size - 1) {
      available.add(index)
    }

    for (argument in to.arguments) {
      val index = available.firstOrNull {
        available.contains(it) && environment.registry.isCastableFromTo(from.arguments[it], argument)
      }

      if (index == null) {
        throw SentoException("Unable to generate @{0} binding for ''{1}#{2}'' method - argument ''{3}'' didn''t match any listener parameters.",
            context.annotation.type.simpleName, context.clazz.type.className, context.method.name, argument.className)
      }

      result.add(ArgumentSpec(index, argument))
      available.remove(index)
    }

    return result
  }

  private val ListenerClassSpec.listenerParent: Type
    get() = if (listener.access.isInterface) Types.OBJECT else listener.type

  private val ListenerClassSpec.listenerInterfaces: Array<Type>
    get() = if (listener.access.isInterface) arrayOf(listener.type) else emptyArray()

  private data class ListenerSpec(
      public val type: Type,
      public val target: Type,
      public val callback: MethodSpec,
      public val method: MethodSpec,
      public val args: Collection<ArgumentSpec>
  )

  private data class ArgumentSpec(
      public val index: Int,
      public val type: Type
  )
}
