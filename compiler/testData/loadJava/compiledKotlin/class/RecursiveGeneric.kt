//ALLOW_AST_ACCESS
// !LANGUAGE: +NewInference
package test

interface Rec<R, out T: Rec<R, T>> {
    fun t(): T
}

interface Super {
    fun foo(p: Rec<*, *>) = p.t()
}