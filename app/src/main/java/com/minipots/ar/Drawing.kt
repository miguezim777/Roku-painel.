package com.minipots.ar

import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

private fun newBuffer(): Int { val ids=IntArray(1); glGenBuffers(1,ids,0); return ids[0] }

private fun floats(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

class Drawing {
    private val cameraProgram = program("""#version 300 es
        in vec2 aPosition; in vec2 aTexCoord; out vec2 vTexCoord;
        void main() { gl_Position=vec4(aPosition,0.999,1.0); vTexCoord=aTexCoord; }
    """, """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float; uniform samplerExternalOES uTexture; in vec2 vTexCoord; out vec4 color;
        void main() { color=texture(uTexture,vTexCoord); }
    """)
    private val meshProgram = program("""#version 300 es
        in vec3 aPosition; in vec3 aNormal; uniform mat4 uMvp; uniform mat4 uModel;
        out vec3 vNormal; out vec3 vPosition;
        void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vPosition=(uModel*vec4(aPosition,1.0)).xyz; vNormal=normalize(mat3(uModel)*aNormal); }
    """, """#version 300 es
        precision mediump float; in vec3 vNormal; in vec3 vPosition; uniform vec4 uColor; out vec4 color;
        void main(){ vec3 n=normalize(vNormal); float diffuse=0.60+0.34*max(dot(n,normalize(vec3(0.4,0.9,0.5))),0.0); float rim=pow(1.0-abs(n.y),3.0)*0.12; color=vec4(min(uColor.rgb*(diffuse+rim)+0.05,1.0),uColor.a); }
    """)
    private val quad = floats(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val uv = floats(FloatArray(8))
    private val cameraBuffer = newBuffer()
    private val planeBuffer = newBuffer()
    private val cup = Mesh(cupVertices())
    private val disk = Mesh(cylinder(.079f, .012f, 24))
    private val sphere = Mesh(sphereVertices())
    private val box = Mesh(boxVertices())
    private val coin = Mesh(cylinder(.025f,.009f,20))
    private val star = Mesh(starVertices())

    fun background(frame: Frame, texture: Int) {
        quad.position(0); uv.position(0)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
        quad.position(0); uv.position(0)
        val vertices = FloatArray(16)
        for (i in 0..3) { vertices[i*4]=quad.get(i*2); vertices[i*4+1]=quad.get(i*2+1); vertices[i*4+2]=uv.get(i*2); vertices[i*4+3]=uv.get(i*2+1) }
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glUseProgram(cameraProgram)
        glBindBuffer(GL_ARRAY_BUFFER, cameraBuffer); glBufferData(GL_ARRAY_BUFFER, vertices.size*4, floats(vertices), GL_DYNAMIC_DRAW)
        val p=glGetAttribLocation(cameraProgram,"aPosition"); val t=glGetAttribLocation(cameraProgram,"aTexCoord")
        glEnableVertexAttribArray(p); glVertexAttribPointer(p,2,GL_FLOAT,false,16,0)
        glEnableVertexAttribArray(t); glVertexAttribPointer(t,2,GL_FLOAT,false,16,8)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture); glUniform1i(glGetUniformLocation(cameraProgram,"uTexture"),0)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4); glDisableVertexAttribArray(p); glDisableVertexAttribArray(t); glBindBuffer(GL_ARRAY_BUFFER,0); glDepthMask(true)
    }

    fun plane(polygon: FloatBuffer, model: FloatArray, vp: FloatArray) {
        val n=polygon.limit()/2; if(n<3) return
        val data=FloatArray(n*6); for (i in 0 until n) { data[i*6]=polygon.get(i*2); data[i*6+1]=.001f; data[i*6+2]=polygon.get(i*2+1); data[i*6+4]=1f }
        glBindBuffer(GL_ARRAY_BUFFER,planeBuffer); glBufferData(GL_ARRAY_BUFFER,data.size*4,floats(data),GL_DYNAMIC_DRAW)
        render(planeBuffer,n,GL_TRIANGLE_FAN,model,vp,floatArrayOf(.39f,.71f,.96f,.12f))
    }
    fun pot(base: FloatArray, vp: FloatArray, color: FloatArray, lid: Float) {
        draw(cup,base,vp,floatArrayOf(color[0],color[1],color[2],.87f))
        val rim=base.copyOf(); Matrix.translateM(rim,0,0f,.101f,-.079f); Matrix.rotateM(rim,0,-110f*lid,1f,0f,0f); Matrix.translateM(rim,0,0f,0f,.079f)
        draw(disk,rim,vp,color)
        val knob=rim.copyOf(); Matrix.translateM(knob,0,0f,.015f,0f); Matrix.scaleM(knob,0,.42f,.42f,.42f)
        draw(sphere,knob,vp,floatArrayOf(1f,.95f,.86f,1f))
    }
    fun item(kind: ItemKind, model: FloatArray, vp: FloatArray, color: FloatArray) {
        when(kind) {
            ItemKind.BALL -> draw(sphere,model,vp,color)
            ItemKind.CUBE -> draw(box,model,vp,color)
            ItemKind.COIN -> draw(coin,model,vp,floatArrayOf(1f,.82f,.40f,1f))
            ItemKind.STAR -> draw(star,model,vp,floatArrayOf(1f,.82f,.40f,1f))
            ItemKind.STONE -> { val m=model.copyOf(); Matrix.scaleM(m,0,1.1f,.7f,.8f); draw(sphere,m,vp,color) }
        }
    }
    private fun draw(mesh: Mesh, model: FloatArray, vp: FloatArray, color: FloatArray) = render(mesh.buffer,mesh.count,GL_TRIANGLES,model,vp,color)
    private fun render(buffer: Int, count: Int, primitive: Int, model: FloatArray, vp: FloatArray, color: FloatArray) {
        glUseProgram(meshProgram); val mvp=FloatArray(16); Matrix.multiplyMM(mvp,0,vp,0,model,0)
        glUniformMatrix4fv(glGetUniformLocation(meshProgram,"uMvp"),1,false,mvp,0); glUniformMatrix4fv(glGetUniformLocation(meshProgram,"uModel"),1,false,model,0)
        glUniform4fv(glGetUniformLocation(meshProgram,"uColor"),1,color,0)
        val p=glGetAttribLocation(meshProgram,"aPosition"); val n=glGetAttribLocation(meshProgram,"aNormal")
        glBindBuffer(GL_ARRAY_BUFFER,buffer)
        glEnableVertexAttribArray(p); glVertexAttribPointer(p,3,GL_FLOAT,false,24,0)
        glEnableVertexAttribArray(n); glVertexAttribPointer(n,3,GL_FLOAT,false,24,12)
        glDrawArrays(primitive,0,count); glDisableVertexAttribArray(p); glDisableVertexAttribArray(n); glBindBuffer(GL_ARRAY_BUFFER,0)
    }
    private class Mesh(vertices: FloatArray) {
        val buffer = newBuffer(); val count=vertices.size/6
        init { glBindBuffer(GL_ARRAY_BUFFER,buffer); glBufferData(GL_ARRAY_BUFFER,vertices.size*4,floats(vertices),GL_STATIC_DRAW); glBindBuffer(GL_ARRAY_BUFFER,0) }
    }
    private fun program(vertex: String, fragment: String): Int {
        fun shader(type:Int, src:String): Int { val s=glCreateShader(type); glShaderSource(s,src.trimIndent()); glCompileShader(s); val ok=IntArray(1); glGetShaderiv(s,GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { glGetShaderInfoLog(s) }; return s }
        val v=shader(GL_VERTEX_SHADER,vertex); val f=shader(GL_FRAGMENT_SHADER,fragment); val p=glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p)
        val ok=IntArray(1); glGetProgramiv(p,GL_LINK_STATUS,ok,0); check(ok[0]!=0) { glGetProgramInfoLog(p) }; glDeleteShader(v); glDeleteShader(f); return p
    }
}

private class Builder {
    val a=ArrayList<Float>()
    fun v(x:Float,y:Float,z:Float,nx:Float,ny:Float,nz:Float) { a.add(x); a.add(y); a.add(z); a.add(nx); a.add(ny); a.add(nz) }
    fun tri(p:FloatArray,q:FloatArray,r:FloatArray,n:FloatArray) { for (v in arrayOf(p,q,r)) v(v[0],v[1],v[2],n[0],n[1],n[2]) }
    fun result()=a.toFloatArray()
}
private fun cylinder(radius:Float,height:Float,segments:Int):FloatArray {
    val b=Builder()
    for(i in 0 until segments) {
        val a=2f*PI.toFloat()*i/segments; val c=2f*PI.toFloat()*(i+1)/segments
        val x=cos(a)*radius; val z=sin(a)*radius; val xx=cos(c)*radius; val zz=sin(c)*radius
        val n=floatArrayOf(cos((a+c)/2),0f,sin((a+c)/2))
        b.tri(floatArrayOf(x,0f,z),floatArrayOf(xx,0f,zz),floatArrayOf(xx,height,zz),n)
        b.tri(floatArrayOf(x,0f,z),floatArrayOf(xx,height,zz),floatArrayOf(x,height,z),n)
        b.tri(floatArrayOf(0f,height,0f),floatArrayOf(x,height,z),floatArrayOf(xx,height,zz),floatArrayOf(0f,1f,0f))
        b.tri(floatArrayOf(0f,0f,0f),floatArrayOf(xx,0f,zz),floatArrayOf(x,0f,z),floatArrayOf(0f,-1f,0f))
    }; return b.result()
}
private fun cupVertices():FloatArray {
    val b=Builder(); val count=32; val outer=.075f; val inner=.064f; val h=.10f
    for(i in 0 until count) {
        val a=2f*PI.toFloat()*i/count; val c=2f*PI.toFloat()*(i+1)/count
        fun p(r:Float,y:Float,t:Float)=floatArrayOf(cos(t)*r,y,sin(t)*r)
        val n=floatArrayOf(cos((a+c)/2),0f,sin((a+c)/2)); val ni=floatArrayOf(-n[0],0f,-n[2]); val up=floatArrayOf(0f,1f,0f)
        b.tri(p(outer,0f,a),p(outer,0f,c),p(outer,h,c),n); b.tri(p(outer,0f,a),p(outer,h,c),p(outer,h,a),n)
        b.tri(p(inner,.012f,a),p(inner,h,c),p(inner,.012f,c),ni); b.tri(p(inner,.012f,a),p(inner,h,a),p(inner,h,c),ni)
        b.tri(p(inner,h,a),p(outer,h,a),p(outer,h,c),up); b.tri(p(inner,h,a),p(outer,h,c),p(inner,h,c),up)
        b.tri(floatArrayOf(0f,.012f,0f),p(inner,.012f,a),p(inner,.012f,c),up)
    }; return b.result()
}
private fun sphereVertices():FloatArray {
    val b=Builder(); val lat=10; val lon=16; val radius=.025f
    fun point(i:Int,j:Int):FloatArray { val t=PI.toFloat()*i/lat; val p=2f*PI.toFloat()*j/lon; return floatArrayOf(sin(t)*cos(p),cos(t),sin(t)*sin(p)) }
    fun v(n:FloatArray) = b.v(n[0]*radius,n[1]*radius,n[2]*radius,n[0],n[1],n[2])
    for(i in 0 until lat) for(j in 0 until lon) { val a=point(i,j); val c=point(i+1,j); val d=point(i+1,j+1); val e=point(i,j+1); v(a);v(c);v(d);v(a);v(d);v(e) }; return b.result()
}
private fun boxVertices():FloatArray {
    val b=Builder(); val s=.022f
    fun face(a:FloatArray,c:FloatArray,d:FloatArray,e:FloatArray,n:FloatArray){b.tri(a,c,d,n);b.tri(a,d,e,n)}
    face(floatArrayOf(-s,s,-s),floatArrayOf(s,s,-s),floatArrayOf(s,s,s),floatArrayOf(-s,s,s),floatArrayOf(0f,1f,0f))
    face(floatArrayOf(-s,-s,s),floatArrayOf(s,-s,s),floatArrayOf(s,-s,-s),floatArrayOf(-s,-s,-s),floatArrayOf(0f,-1f,0f))
    face(floatArrayOf(-s,-s,s),floatArrayOf(-s,s,s),floatArrayOf(s,s,s),floatArrayOf(s,-s,s),floatArrayOf(0f,0f,1f))
    face(floatArrayOf(s,-s,-s),floatArrayOf(s,s,-s),floatArrayOf(-s,s,-s),floatArrayOf(-s,-s,-s),floatArrayOf(0f,0f,-1f))
    face(floatArrayOf(s,-s,s),floatArrayOf(s,s,s),floatArrayOf(s,s,-s),floatArrayOf(s,-s,-s),floatArrayOf(1f,0f,0f))
    face(floatArrayOf(-s,-s,-s),floatArrayOf(-s,s,-s),floatArrayOf(-s,s,s),floatArrayOf(-s,-s,s),floatArrayOf(-1f,0f,0f)); return b.result()
}
private fun starVertices():FloatArray {
    val b=Builder(); val n=floatArrayOf(0f,1f,0f)
    for(i in 0 until 10) {
        fun p(j:Int,y:Float):FloatArray { val a=2f*PI.toFloat()*j/10; val r=if(j%2==0) .03f else .013f; return floatArrayOf(cos(a)*r,y,sin(a)*r) }
        val a=p(i,.006f); val c=p((i+1)%10,.006f); val d=p(i,-.006f); val e=p((i+1)%10,-.006f)
        b.tri(floatArrayOf(0f,.006f,0f),a,c,n); b.tri(floatArrayOf(0f,-.006f,0f),e,d,floatArrayOf(0f,-1f,0f))
        val side=floatArrayOf((a[0]+c[0])*15f,0f,(a[2]+c[2])*15f); b.tri(a,d,e,side); b.tri(a,e,c,side)
    }; return b.result()
}
