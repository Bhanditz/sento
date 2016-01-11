package io.sento.compiler.reflect

import io.sento.compiler.common.Opener
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import java.util.concurrent.atomic.AtomicReference

internal data class ClassReference(
    public val access: Int,
    public val type: Type,
    public val parent: Type,
    public val interfaces: Collection<Type>,
    public val opener: Opener
) {
  public fun resolve(): ClassSpec {
    val flags = ClassReader.SKIP_CODE + ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES

    val reader = ClassReader(opener.open())
    val result = AtomicReference<ClassSpec>()

    reader.accept(ClassSpecVisitor(access, type, parent, interfaces, opener) {
      result.set(it)
    }, flags)

    return result.get()
  }
}
