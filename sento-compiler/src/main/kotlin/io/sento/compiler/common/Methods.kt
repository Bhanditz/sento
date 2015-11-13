package io.sento.compiler.common

import io.sento.compiler.model.MethodSpec
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Method

internal object Methods {
  public fun get(spec: MethodSpec): Method {
    return Method(spec.name, spec.type.descriptor)
  }

  public fun get(name: String, returnType: Type, vararg argsType: Type): Method {
    return Method(name, returnType, argsType)
  }

  public fun getConstructor(vararg argsType: Type): Method {
    return Method("<init>", Type.VOID_TYPE, argsType)
  }
}
