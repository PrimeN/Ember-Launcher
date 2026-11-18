package app.ember.tv.player

import app.ember.tv.mediaserver.Chapter

/** Bounded ISO-BMFF chapter reader. Media payloads are skipped by offset, never buffered. */
internal object Mp4Chapters {
    private data class Box(val type:String,val body:Long,val end:Long)
    fun recognizes(head:ByteArray)=head.size>=8 && String(head,4,4,Charsets.US_ASCII) in setOf("ftyp","moov","free","wide","mdat")
    fun read(head:ByteArray,fetch:(Long,Int)->ByteArray?):List<Chapter> {
        var budget=4*1024*1024
        var metadata:LongRange?=null;var readAhead=0
        val cached=ArrayDeque<Pair<Long,ByteArray>>()
        fun bytes(at:Long,size:Int):ByteArray {
            require(at>=0 && size in 0..1024*1024 && at<=Long.MAX_VALUE-size)
            if(at+size<=head.size)return head.copyOfRange(at.toInt(),at.toInt()+size)
            cached.firstOrNull {(offset,data)->at>=offset&&at+size<=offset+data.size}?.let {(offset,data)->return data.copyOfRange((at-offset).toInt(),(at-offset).toInt()+size)}
            val window=metadata
            val ahead=window!=null&&at in window&&size<=64*1024&&at+size-1<=window.last&&readAhead<1024*1024
            val wanted=if(ahead)minOf(maxOf(size,64*1024).toLong(),window!!.last-at+1,(1024*1024-readAhead).toLong()).toInt().coerceAtLeast(size) else size
            require(wanted<=budget);budget-=wanted
            val data=fetch(at,wanted)?.also {require(it.size>=size&&it.size<=wanted)} ?: error("Missing chapter data")
            if(ahead){readAhead+=wanted;cached.addLast(at to data);if(cached.size>8)cached.removeFirst()}
            return data.copyOfRange(0,size)
        }
        fun boxes(start:Long,end:Long):List<Box> {
            val out=mutableListOf<Box>();var at=start
            while(at<=end-8 && out.size<4096) {
                val h=bytes(at,8);var size=u32(h,0);var header=8
                if(size==1L){size=u64(bytes(at+8,8),0);header=16}
                if(size==0L){if(end==Long.MAX_VALUE)break;size=end-at}
                require(size>=header && size<=end-at)
                out+=Box(String(h,4,4,Charsets.US_ASCII),at+header,at+size);at+=size
                // Once moov is found no later top-level data is needed.
                if(start==0L && out.last().type=="moov")break
            }
            return out
        }
        fun children(box:Box)=boxes(box.body,box.end)
        fun payload(box:Box)=bytes(box.body,(box.end-box.body).also {require(it<=1024*1024)}.toInt())
        fun table(box:Box?,stride:Int):List<LongArray> {
            box ?: return emptyList()
            val b=payload(box);val count=u32(b,4).also {require(it<=20000)}.toInt()
            require(8L+count.toLong()*stride<=b.size)
            return List(count){i->LongArray(stride/4){j->u32(b,8+i*stride+j*4)}}
        }
        try {
            if(!recognizes(head))return emptyList()
            val movie=boxes(0,Long.MAX_VALUE).firstOrNull {it.type=="moov"} ?: return emptyList()
            metadata=movie.body until movie.end
            val movieChildren=children(movie)
            val chapterList=movieChildren.firstOrNull {it.type=="udta"}?.let(::children)?.firstOrNull {it.type=="chpl"}
            if(chapterList!=null) {
                val b=payload(chapterList);var at=if(b.firstOrNull()?.toInt()==1)8 else 4
                require(at<b.size);val count=b[at++].toInt() and 255
                val chapters=List(count){i->
                    val time=u64(b,at)/10000;at+=8;require(at<b.size)
                    val n=b[at++].toInt() and 255;require(at+n<=b.size)
                    val name=String(b,at,n,Charsets.UTF_8).trim();at+=n
                    Chapter(name.ifBlank {"Chapter ${i+1}"},time)
                }
                if(chapters.isNotEmpty())return chapters.sortedBy {it.startMs}.distinctBy {it.startMs}
            }
            data class Track(val id:Long,val boxes:List<Box>)
            val tracks=movieChildren.filter {it.type=="trak"}.mapNotNull {trak->
                val parts=children(trak);val h=parts.firstOrNull {it.type=="tkhd"} ?: return@mapNotNull null
                val b=payload(h);Track(u32(b,if(b[0].toInt()==1)20 else 12),parts)
            }
            val refs=tracks.flatMap {track->track.boxes.firstOrNull {it.type=="tref"}?.let(::children).orEmpty().filter {it.type=="chap"}.flatMap {
                val b=payload(it);require(b.size%4==0);List(b.size/4){i->u32(b,i*4)}
            }}.toSet()
            val mvhd=movieChildren.firstOrNull {it.type=="mvhd"}?.let(::payload)
            val movieScale=mvhd?.let {u32(it,if(it[0].toInt()==1)20 else 12)} ?: 0L
            for(track in tracks.filter {it.id in refs}) {
                val mdia=track.boxes.firstOrNull {it.type=="mdia"}?.let(::children) ?: continue
                val handler=mdia.firstOrNull {it.type=="hdlr"}?.let(::payload) ?: continue
                if(handler.size<12 || String(handler,8,4,Charsets.US_ASCII) !in setOf("text","sbtl","subt"))continue
                val mdhd=payload(mdia.first {it.type=="mdhd"});val scale=u32(mdhd,if(mdhd[0].toInt()==1)20 else 12);require(scale>0)
                val stbl=mdia.firstOrNull {it.type=="minf"}?.let(::children)?.firstOrNull {it.type=="stbl"}?.let(::children) ?: continue
                fun box(name:String)=stbl.firstOrNull {it.type==name}
                val sz=payload(box("stsz") ?: continue);val fixed=u32(sz,4);val count=u32(sz,8).also {require(it<=2000)}.toInt()
                val sizes=List(count){i->if(fixed>0)fixed else u32(sz,12+i*4)}
                require(sizes.all {it in 2..65536})
                val stts=table(box("stts"),8);val times=mutableListOf<Long>();var time=0L
                for(row in stts){require(row[0]<=count-times.size);repeat(row[0].toInt()){times+=time;require(time<=Long.MAX_VALUE-row[1]);time+=row[1]}}
                require(times.size==count)
                val stsc=table(box("stsc"),12);require(stsc.isNotEmpty() && stsc.first()[0]==1L && stsc.zipWithNext().all {it.first[0]<it.second[0]})
                val chunkBox=box("stco") ?: box("co64") ?: continue;val cb=payload(chunkBox)
                val chunks=u32(cb,4).also {require(it<=20000)}.toInt();val stride=if(chunkBox.type=="co64")8 else 4
                require(8L+chunks.toLong()*stride<=cb.size)
                var adjustment=0.0
                track.boxes.firstOrNull {it.type=="edts"}?.let(::children)?.firstOrNull {it.type=="elst"}?.let {edit->
                    val b=payload(edit);val v=b[0].toInt();val n=u32(b,4).also {require(it<=100)}.toInt();var at=8
                    for(i in 0 until n){
                        val duration=if(v==1)u64(b,at) else u32(b,at);at+=if(v==1)8 else 4
                        require(at+(if(v==1)8 else 4)+4<=b.size)
                        val media=if(v==1)java.nio.ByteBuffer.wrap(b,at,8).long else java.nio.ByteBuffer.wrap(b,at,4).int.toLong();at+=if(v==1)8 else 4
                        require(b[at].toInt()==0 && b[at+1].toInt()==1 && b[at+2].toInt()==0 && b[at+3].toInt()==0);at+=4
                        if(media==-1L){require(movieScale>0);adjustment+=duration*1000.0/movieScale}else {require(media>=0);adjustment-=media*1000.0/scale;break}
                    }
                }
                val out=mutableListOf<Chapter>();var sample=0;var mapping=0
                for(chunk in 1..chunks) {
                    while(mapping+1<stsc.size && stsc[mapping+1][0]<=chunk)mapping++
                    val n=stsc[mapping][1];require(n in 1..2000 && sample+n<=count)
                    var offset=if(stride==8)u64(cb,8+(chunk-1)*8) else u32(cb,8+(chunk-1)*4)
                    val limit=sample+n.toInt()
                    while(sample<limit) {
                        var endSample=sample;var blockSize=0
                        while(endSample<limit && blockSize+sizes[endSample]<=1024*1024){blockSize+=sizes[endSample].toInt();endSample++}
                        require(endSample>sample)
                        val block=bytes(offset,blockSize);var within=0
                        while(sample<endSample) {
                            val size=sizes[sample].toInt()
                            val length=((block[within].toInt() and 255) shl 8) or (block[within+1].toInt() and 255)
                            require(length<=size-2)
                            val text=block.copyOfRange(within+2,within+2+length)
                            val name=String(text,if(text.size>=2 && ((text[0]==0xfe.toByte() && text[1]==0xff.toByte()) || (text[0]==0xff.toByte() && text[1]==0xfe.toByte())))Charsets.UTF_16 else Charsets.UTF_8).trim()
                            val ms=(times[sample]*1000.0/scale+adjustment).toLong().coerceAtLeast(0)
                            out+=Chapter(name.ifBlank {"Chapter ${sample+1}"},ms);within+=size;sample++
                        }
                        offset+=blockSize
                    }
                }
                require(sample==count)
                if(out.isNotEmpty())return out.sortedBy {it.startMs}.distinctBy {it.startMs}
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){return emptyList()}
        return emptyList()
    }
    private fun u32(b:ByteArray,at:Int):Long {require(at>=0 && at<=b.size-4);return java.nio.ByteBuffer.wrap(b,at,4).int.toLong() and 0xffffffffL}
    private fun u64(b:ByteArray,at:Int):Long {require(at>=0 && at<=b.size-8);return java.nio.ByteBuffer.wrap(b,at,8).long.also {require(it>=0)}}
}
