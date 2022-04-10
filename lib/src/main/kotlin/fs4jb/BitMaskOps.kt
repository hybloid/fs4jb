package fs4jb

class BitMaskOps {
    companion object {
        fun Int.check(n : Int) = (this and (1 shl n)) != 0
        fun Int.set1(n : Int) = this or (1 shl n)
        fun Int.set0(n : Int) = this and ((1 shl n).inv())
        fun Int.toggle(n : Int) = this xor (1 shl n)
    }
}