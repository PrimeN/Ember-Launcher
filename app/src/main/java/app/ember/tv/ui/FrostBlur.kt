package app.ember.tv.ui

/** Separable box blur for tiny, opaque backdrop samples; no work on the UI thread. */
internal object FrostBlur {
    fun apply(pixels:IntArray,width:Int,height:Int,radius:Int):IntArray {
        require(width>0 && height>0 && width.toLong()*height==pixels.size.toLong())
        val r=radius.coerceIn(0,32)
        if(r==0)return pixels.copyOf()
        val intermediate=IntArray(pixels.size);val result=IntArray(pixels.size);val size=2*r+1
        fun pass(input:IntArray,output:IntArray,horizontal:Boolean) {
            val lines=if(horizontal)height else width
            val length=if(horizontal)width else height
            fun index(line:Int,pos:Int)=if(horizontal)line*width+pos else pos*width+line
            for(line in 0 until lines) {
                val sum=LongArray(4)
                fun add(pixel:Int,sign:Int) {for(c in 0..3)sum[c]+=((pixel ushr (c*8)) and 255)*sign}
                for(pos in -r..r)add(input[index(line,pos.coerceIn(0,length-1))],1)
                for(pos in 0 until length) {
                    var value=0
                    for(c in 0..3)value=value or ((sum[c]/size).toInt() shl (c*8))
                    output[index(line,pos)]=value
                    add(input[index(line,(pos-r).coerceIn(0,length-1))],-1)
                    add(input[index(line,(pos+r+1).coerceIn(0,length-1))],1)
                }
            }
        }
        pass(pixels,intermediate,true);pass(intermediate,result,false)
        return result
    }
}
