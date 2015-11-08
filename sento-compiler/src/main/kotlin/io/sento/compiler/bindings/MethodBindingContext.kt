package io.sento.compiler.bindings

import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.common.TypeFactory
import io.sento.compiler.model.ClassSpec
import io.sento.compiler.model.MethodSpec
import org.objectweb.asm.MethodVisitor
import java.util.NoSuchElementException

internal class MethodBindingContext<A : Annotation>(
    public val method: MethodSpec,
    public val clazz: ClassSpec,
    public val annotation: A,
    public val visitor: MethodVisitor,
    public val variables: Map<String, Int>,
    public val factory: TypeFactory,
    public val environment: GenerationEnvironment
) {
  public fun variable(name: String): Int {
    return variables[name] ?: throw NoSuchElementException("Unknown variable \"$name\"")
  }
}
