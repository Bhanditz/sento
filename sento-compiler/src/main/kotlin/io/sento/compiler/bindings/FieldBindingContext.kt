package io.sento.compiler.bindings

import io.sento.compiler.specs.ClassSpec
import io.sento.compiler.specs.FieldSpec
import org.objectweb.asm.MethodVisitor

internal class FieldBindingContext<A : Annotation>(
    public val field: FieldSpec,
    public val clazz: ClassSpec,
    public val annotation: A,
    public val visitor: MethodVisitor,
    public val variables: Map<String, Int>
) {
  public fun variable(name: String): Int {
    return variables.get(name) ?: throw UnsupportedOperationException("Unknown variable \"$name\"")
  }
}
