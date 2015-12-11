package io.sento.compiler.bindings

import io.sento.compiler.ContentGenerator
import io.sento.compiler.GenerationEnvironment
import io.sento.compiler.reflection.ClassSpec

internal class ContentGeneratorFactory private constructor() {
  public companion object {
    public fun from(environment: GenerationEnvironment): ContentGeneratorFactory {
      return ContentGeneratorFactory()
    }
  }

  public fun createBinding(clazz: ClassSpec): ContentGenerator {
    return SentoBindingContentGenerator(clazz)
  }

  public fun createFactory(bindings: Collection<ClassSpec>): ContentGenerator {
    return SentoFactoryContentGenerator(bindings)
  }
}
