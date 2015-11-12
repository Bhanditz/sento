package io.sento.compiler.bindings.fields

import io.sento.compiler.GeneratedContent
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.common.Annotations
import io.sento.compiler.common.Types
import io.sento.compiler.model.ResourceBindingSpec
import org.objectweb.asm.commons.Method

internal class ResourceBindingGenerator(
    private val bindings: Collection<ResourceBindingSpec>
) : FieldBindingGenerator {
  override fun bind(context: FieldBindingContext, environment: GenerationEnvironment): List<GeneratedContent> {
    val adapter = context.adapter
    val annotation = context.annotation

    val field = context.field
    val clazz = context.clazz

    adapter.loadArg(context.variable("target"))
    adapter.loadArg(context.variable("finder"))
    adapter.loadArg(context.variable("source"))

    adapter.invokeInterface(Types.TYPE_FINDER, Method.getMethod("android.content.res.Resources resources (Object))"))
    adapter.push(Annotations.id(annotation))

    val binding = bindings.first {
      environment.registry.isSubclassOf(field.type, it.type)
    }

    adapter.invokeVirtual(Types.TYPE_RESOURCES, Method(binding.getter.name, binding.getter.type.descriptor))
    adapter.putField(clazz.type, field.name, field.type)

    return emptyList()
  }
}