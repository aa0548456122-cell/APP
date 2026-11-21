package il.co.shoppingoffline
import android.graphics.Bitmap
import ai.onnxruntime.*
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Local Mishkefet-v1 ONNX inference with line segmentation and CTC greedy decoding. */
internal class MishkefetOcr(private val dir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val chars: List<String>
    private val classes: Int
    private val dim: Int
    private val weights: FloatArray
    private val bias: FloatArray
    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        session = env.createSession(File(dir,"backbone.fp16.onnx").absolutePath,opts)
        opts.close()
        val a=JSONArray(File(dir,"charset.json").readText())
        chars=(0 until a.length()).map { a.getString(it) }
        val b=ByteBuffer.wrap(File(dir,"head.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(b.array(),0,4,Charsets.US_ASCII)=="MKH1")
        classes=b.getInt(4); dim=b.getInt(8)
        require(classes==chars.size && dim==512 && b.capacity()>=12+4*(classes*dim+classes))
        weights=FloatArray(classes*dim); bias=FloatArray(classes)
        var p=12
        for(i in weights.indices){weights[i]=b.getFloat(p);p+=4}
        for(i in bias.indices){bias[i]=b.getFloat(p);p+=4}
    }
    fun readPage(bitmap:Bitmap):List<String>{
        val lines=segment(bitmap)
        return try { lines.mapNotNull { try{readLine(it).takeIf(String::isNotBlank)}catch(_:Exception){null} } }
        finally { lines.forEach{if(it!==bitmap&&!it.isRecycled)it.recycle()} }
    }
    private fun segment(b:Bitmap):List<Bitmap>{
        val w=b.width;val h=b.height;val px=IntArray(w*h);b.getPixels(px,0,w,0,0,w,h)
        val active=BooleanArray(h)
        val step=max(1,w/1200)
        for(y in 0 until h){var n=0;var ink=0;var x=0
            while(x<w){val c=px[y*w+x];val g=(android.graphics.Color.red(c)*299+android.graphics.Color.green(c)*587+android.graphics.Color.blue(c)*114)/1000
                if(g<170)ink++;n++;x+=step}
            active[y]=n>0&&ink*100/n>=1
        }
        val bands=mutableListOf<Pair<Int,Int>>();var start=-1
        for(y in 0 until h){if(active[y]&&start<0)start=y
            if((!active[y]||y==h-1)&&start>=0){val end=if(active[y]&&y==h-1)y+1 else y
                if(end-start>=max(8,h/250))bands.add(start to end);start=-1}}
        if(bands.isEmpty())return listOf(Bitmap.createBitmap(b))
        val merged=mutableListOf<Pair<Int,Int>>()
        for(v in bands){val last=merged.lastOrNull()
            if(last!=null&&v.first-last.second<max(5,h/100))merged[merged.lastIndex]=last.first to v.second else merged.add(v)}
        return merged.map{(top,bottom)->val y=max(0,top-5);val end=min(h,bottom+5);Bitmap.createBitmap(b,0,y,w,end-y)}
    }
    private fun readLine(b:Bitmap):String{
        val width=(b.width.toDouble()*64/max(1,b.height)).roundToInt().coerceIn(16,2560)
        val scaled=Bitmap.createScaledBitmap(b,width,64,true);val pixels=IntArray(width*64)
        scaled.getPixels(pixels,0,width,0,0,width,64);if(scaled!==b)scaled.recycle()
        val input=FloatArray(pixels.size){i->val c=pixels[i];val g=(android.graphics.Color.red(c)*19595+android.graphics.Color.green(c)*38470+android.graphics.Color.blue(c)*7471+32768) shr 16;1f-g.coerceIn(0,255)/255f}
        val tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,1,64,width.toLong()))
        val result=session.run(mapOf(session.inputNames.first() to tensor))
        try{
            val batch=(result[0].value as Array<*>)[0] as Array<*>
            val out=StringBuilder();var previous=-1
            for(t in batch.indices){val f=batch[t] as FloatArray;var best=0;var scoreBest=Float.NEGATIVE_INFINITY
                for(k in 0 until classes){var s=bias[k];for(d in 0 until dim)s+=f[d]*weights[k*dim+d];if(s>scoreBest){scoreBest=s;best=k}}
                if(best!=previous&&best!=0)out.append(chars[best]);previous=best}
            out.toString().trim()
        }finally{result.close();tensor.close()}
    }
    override fun close(){session.close()}
}