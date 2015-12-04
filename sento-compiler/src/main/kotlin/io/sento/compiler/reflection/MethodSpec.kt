package io.sento.compiler.reflection

import io.sento.compiler.annotations.AnnotationProxy
import io.sento.compiler.common.Types
import org.objectweb.asm.Type
import java.util.ArrayList

internal data class MethodSpec(
    public val access: Int,
    public val name: String,
    public val type: Type,
    public val signature: String?,
    public val annotations: Collection<AnnotationSpec>
) {
  internal class Builder(val access: Int, val name: String, val type: Type, val signature: String?) {
    private val annotations = ArrayList<AnnotationSpec>()

    public fun annotation(annotation: AnnotationSpec): Builder = apply {
      annotations.add(annotation)
    }

    public fun build(): MethodSpec {
      return MethodSpec(access, name, type, signature, annotations)
    }
  }

  public val returns by lazy(LazyThreadSafetyMode.NONE) {
    type.returnType
  }

  public val arguments by lazy(LazyThreadSafetyMode.NONE) {
    type.argumentTypes.orEmpty()
  }

  public inline fun <reified A : Any> getAnnotation(): A? {
    return getAnnotation(A::class.java)
  }

  public fun <A> getAnnotation(annotation: Class<A>): A? {
    return AnnotationProxy.create(annotation, annotations.firstOrNull {
      it.type == Types.getAnnotationType(annotation)
    } ?: return null)
  }
}
