package io.sento.compiler.generator

import io.sento.compiler.ClassWriter
import io.sento.compiler.ContentGenerator
import io.sento.compiler.GeneratedContent
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.common.GeneratorAdapter
import io.sento.compiler.common.Methods
import io.sento.compiler.common.Types
import io.sento.compiler.common.isPublic
import io.sento.compiler.model.BindingSpec
import io.sento.compiler.model.ViewOwner
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PROTECTED
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ASM5

internal class SentoBindingContentGenerator(private val binding: BindingSpec) : ContentGenerator {
  public companion object {
    private const val ARGUMENT_TARGET = 0
    private const val ARGUMENT_SOURCE = 1
    private const val ARGUMENT_FINDER = 2
  }

  override fun generate(environment: GenerationEnvironment): Collection<GeneratedContent> {
    return listOf(onCreatePatchedClassGeneratedContent(binding, environment)) + binding.listeners.flatMap {
      ListenerBindingContentGenerator(it).generate(environment)
    }
  }

  private fun onCreatePatchedClassGeneratedContent(binding: BindingSpec, environment: GenerationEnvironment): GeneratedContent {
    return GeneratedContent.from(binding.clazz.type, mapOf(), environment.newClass {
      onCreatePatchedClassForBinding(this, binding, environment)
      onCreateSyntheticFieldsForListeners(this, binding, environment)
      onCreateSyntheticFieldsForViews(this, binding, environment)
      onCreateSyntheticMethodsForListeners(this, binding, environment)

      newMethod(environment.naming.getSyntheticBindMethodSpec()) {
        VariablesContext().apply {
          onCreateLocalVariablesFromArgs(this@newMethod, binding, this, environment)
          onCreateLocalVariablesForViews(this@newMethod, binding, this, environment)
          onEnforceRequiredViewTargets(this@newMethod, binding, this, environment)

          onBindViewTargetFields(this@newMethod, binding, this, environment)
          onBindSyntheticViewFields(this@newMethod, binding, this, environment)

          onBindSyntheticListenerFields(this@newMethod, binding, this, environment)
          onBindSyntheticListenerTargets(this@newMethod, binding, this, environment)
        }
      }

      newMethod(environment.naming.getSyntheticUnbindMethodSpec()) {
        VariablesContext().apply {
          onCreateLocalVariablesFromArgs(this@newMethod, binding, this, environment)

          onUnbindSyntheticListenerTargets(this@newMethod, binding, this, environment)
          onUnbindSyntheticListenerFields(this@newMethod, binding, this, environment)

          onUnbindSyntheticViewFields(this@newMethod, binding, this, environment)
          onUnbindViewTargetFields(this@newMethod, binding, this, environment)
        }
      }
    })
  }

  private fun onCreateLocalVariablesFromArgs(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    variables.target(adapter.newLocal(binding.clazz.type).apply {
      adapter.loadArg(ARGUMENT_TARGET)
      adapter.checkCast(binding.clazz.type)
      adapter.storeLocal(this)
    })
  }

  private fun onCreateLocalVariablesForViews(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.views.distinctBy { it.id }.forEach {
      variables.view(it.id, adapter.newLocal(Types.VIEW).apply {
        adapter.loadArg(ARGUMENT_FINDER)
        adapter.push(it.id)

        adapter.loadArg(ARGUMENT_SOURCE)
        adapter.invokeInterface(Types.FINDER, Methods.get("find", Types.VIEW, Types.INT, Types.OBJECT))

        adapter.storeLocal(this)
      })
    }
  }

  private fun onEnforceRequiredViewTargets(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.views.filter { !it.optional }.distinctBy { it.id }.forEach {
      adapter.loadArg(ARGUMENT_FINDER)
      adapter.push(it.id)

      adapter.loadLocal(variables.view(it.id))
      adapter.loadArg(ARGUMENT_SOURCE)
      adapter.push(it.owner.name)

      adapter.invokeInterface(Types.FINDER, Methods.get("require", Types.VOID, Types.INT, Types.VIEW, Types.OBJECT, Types.STRING))
    }
  }

  private fun onBindSyntheticViewFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.views.filter { it.owner is ViewOwner.Method }.distinctBy { it.id }.forEach {
      adapter.loadLocal(variables.target())
      adapter.loadLocal(variables.view(it.id))
      adapter.putField(it.clazz, environment.naming.getSyntheticFieldName(it), Types.VIEW)
    }
  }

  private fun onUnbindSyntheticViewFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.views.filter { it.owner is ViewOwner.Method }.distinctBy { it.id }.forEach {
      adapter.loadLocal(variables.target())
      adapter.pushNull()
      adapter.putField(it.clazz, environment.naming.getSyntheticFieldName(it), Types.VIEW)
    }
  }

  private fun onBindSyntheticListenerFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.listeners.forEach {
      adapter.loadLocal(variables.target())
      adapter.newInstance(it.type, Methods.getConstructor(it.clazz)) {
        adapter.loadLocal(variables.target())
      }
      adapter.putField(it.clazz, environment.naming.getSyntheticFieldName(it), it.listener.listener)
    }
  }

  private fun onUnbindSyntheticListenerFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.listeners.forEach {
      adapter.loadLocal(variables.target())
      adapter.pushNull()
      adapter.putField(it.clazz, environment.naming.getSyntheticFieldName(it), it.listener.listener)
    }
  }

  private fun onBindSyntheticListenerTargets(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.listeners.forEach {
      for (view in it.views) {
        val label = adapter.newLabel()
        val name = environment.naming.getSyntheticFieldName(view)

        if (view.optional) {
          adapter.loadLocal(variables.target())
          adapter.getField(it.clazz, name, Types.VIEW)
          adapter.ifNull(label)
        }

        adapter.loadLocal(variables.target())
        adapter.getField(it.clazz, name, Types.VIEW)

        if (it.listener.owner.type != Types.VIEW) {
          adapter.checkCast(it.listener.owner)
        }

        adapter.loadLocal(variables.target())
        adapter.getField(it.clazz, environment.naming.getSyntheticFieldName(it), it.listener.listener)

        adapter.invokeVirtual(it.listener.owner, it.listener.setter)
        adapter.mark(label)
      }
    }
  }

  private fun onUnbindSyntheticListenerTargets(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.listeners.forEach {
      for (view in it.views) {
        val label = adapter.newLabel()
        val name = environment.naming.getSyntheticFieldName(view)

        if (view.optional) {
          adapter.loadLocal(variables.target())
          adapter.getField(it.clazz, name, Types.VIEW)
          adapter.ifNull(label)
        }

        adapter.loadLocal(variables.target())
        adapter.getField(it.clazz, name, Types.VIEW)

        if (it.listener.owner.type != Types.VIEW) {
          adapter.checkCast(it.listener.owner)
        }

        if (it.listener.setter != it.listener.unsetter) {
          adapter.loadLocal(variables.target())
          adapter.getField(it.clazz, environment.naming.getSyntheticFieldName(it), it.listener.listener)
        }

        if (it.listener.setter == it.listener.unsetter) {
          adapter.pushNull()
        }

        adapter.invokeVirtual(it.listener.owner, it.listener.unsetter)
        adapter.mark(label)
      }
    }
  }

  private fun onBindViewTargetFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.bindings.forEach {
      adapter.loadLocal(variables.target())
      adapter.loadLocal(variables.view(it.views.first().id))

      if (it.field.type != Types.VIEW) {
        adapter.checkCast(it.field.type)
      }

      adapter.putField(it.clazz, it.field)
    }
  }

  private fun onUnbindViewTargetFields(adapter: GeneratorAdapter, binding: BindingSpec, variables: VariablesContext, environment: GenerationEnvironment) {
    binding.bindings.forEach {
      adapter.loadLocal(variables.target())
      adapter.pushNull()
      adapter.putField(it.clazz, it.field)
    }
  }

  private fun onCreatePatchedClassForBinding(writer: ClassWriter, binding: BindingSpec, environment: GenerationEnvironment) {
    ClassReader(binding.clazz.opener.open()).accept(object : ClassVisitor(ASM5, writer) {
      override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        super.visit(version, onPatchClassAccessFlags(binding, access), name, signature, superName, interfaces)
      }

      override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor {
        return super.visitField(onPatchFieldAccessFlags(binding, access, name), name, desc, signature, value)
      }

      private fun onPatchFieldAccessFlags(binding: BindingSpec, access: Int, name: String): Int {
        return if (!binding.bindings.any { it.field.name == name }) access else {
          access and ACC_FINAL.inv()
        }
      }

      private fun onPatchClassAccessFlags(binding: BindingSpec, access: Int): Int {
        return access and ACC_PRIVATE.inv() and ACC_PROTECTED.inv() or ACC_PUBLIC
      }
    }, ClassReader.SKIP_FRAMES)
  }

  private fun onCreateSyntheticFieldsForViews(writer: ClassWriter, binding: BindingSpec, environment: GenerationEnvironment) {
    binding.views.filter { it.owner is ViewOwner.Method }.distinctBy { it.id }.forEach {
      writer.visitField(ACC_PRIVATE + ACC_SYNTHETIC, environment.naming.getSyntheticFieldName(it), Types.VIEW)
    }
  }

  private fun onCreateSyntheticFieldsForListeners(writer: ClassWriter, binding: BindingSpec, environment: GenerationEnvironment) {
    binding.listeners.distinctBy { it.method.name to it.annotation.type }.forEach {
      writer.visitField(ACC_PRIVATE + ACC_SYNTHETIC, environment.naming.getSyntheticFieldName(it), it.listener.listener.type)
    }
  }

  private fun onCreateSyntheticMethodsForListeners(writer: ClassWriter, binding: BindingSpec, environment: GenerationEnvironment) {
    binding.listeners.filter { !it.method.isPublic }.forEach {
      writer.newMethod(ACC_PUBLIC + ACC_STATIC + ACC_SYNTHETIC, environment.naming.getSyntheticAccessor(binding.clazz, it.method)) {
        invokeVirtual(binding.clazz, it.method.apply {
          for (index in 0..arguments.size) {
            loadArg(index)
          }
        })
      }
    }
  }
}
