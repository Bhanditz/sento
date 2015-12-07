package io.sento.compiler.model

import io.sento.compiler.bindings.ListenerBinder
import io.sento.compiler.reflection.AnnotationSpec
import io.sento.compiler.reflection.ClassSpec
import io.sento.compiler.reflection.MethodSpec

internal data class ListenerTargetSpec(
    public val clazz: ClassSpec,
    public val method: MethodSpec,
    public val annotation: AnnotationSpec,
    public val generator: ListenerBinder,
    public val optional: Boolean
)
