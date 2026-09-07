package com.vicenights.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** A self-contained neon driving experience: no licensed imagery, assets, or network calls. */
class ViceNightsView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var now = 0L; private var last = 0L; private var speed = 0f; private var heading = -1.57f
    private var x = 0f; private var y = 0f; private var steer = 0f; private var gas = false; private var paused = false
    private var timeLeft = 78f; private var score = 0; private var target = PointF(520f, -260f)
    private val rain = List(96) { Rain(Random.nextFloat()*2200-1100, Random.nextFloat()*1500-750, 12+Random.nextInt(28)) }
    private val traffic = List(14) { i -> Car(i*155f-900, if (i%2==0) -130f else 160f, if(i%2==0) 0f else Math.PI.toFloat(), .25f+Random.nextFloat()*.28f, i%3==0) }
    private data class Rain(var x:Float,var y:Float,val len:Int); private data class Car(var x:Float,var y:Float,var dir:Float,var velocity:Float,val pink:Boolean)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null); isFocusable = true }
    override fun onDraw(c: Canvas) { super.onDraw(c); val dt = if(last==0L)0f else min(.04f,(System.nanoTime()-last)/1e9f); last=System.nanoTime(); now+=16
        if(!paused) update(dt); scene(c); if(!paused) postInvalidateOnAnimation()
    }
    private fun update(dt:Float) {
        speed = (speed + if(gas) 48f*dt else -20f*dt).coerceIn(0f, 33f)
        heading += steer * (0.55f + speed/38f) * dt
        x += cos(heading)*speed*dt; y += sin(heading)*speed*dt; timeLeft -= dt
        if (hypot((x-target.x).toDouble(),(y-target.y).toDouble()) < 52) { score++; timeLeft=(timeLeft+12).coerceAtMost(99f); target=PointF(Random.nextInt(-800,850).toFloat(), if(Random.nextBoolean()) -260f else 260f) }
        if(timeLeft<=0) { timeLeft=78f; score=0; x=0f;y=0f; speed=0f }
        traffic.forEach { it.x += cos(it.dir)*it.velocity*60*dt; it.y += sin(it.dir)*it.velocity*60*dt; if(abs(it.x)>1100)it.x*=-1 }
        rain.forEach { it.x-=180*dt;it.y+=440*dt;if(it.y>800){it.y=-800f;it.x=Random.nextFloat()*2200-1100} }
    }
    private fun scene(c:Canvas) {
        val w=width.toFloat();val h=height.toFloat();
        p.shader=LinearGradient(0f,0f,0f,h,intArrayOf(Color.rgb(7,11,31),Color.rgb(19,20,52),Color.rgb(60,27,66)),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p);p.shader=null
        // moon and atmospheric horizon
        glow.color=Color.argb(35,255,196,228); glow.maskFilter=BlurMaskFilter(34f,BlurMaskFilter.Blur.NORMAL);c.drawCircle(w*.78f,h*.16f,80f,glow);glow.maskFilter=null;p.color=Color.rgb(255,220,218);c.drawCircle(w*.78f,h*.16f,48f,p)
        c.save();c.translate(w/2-x,h/2-y); city(c); target(c); traffic.forEach { car(c,it.x,it.y,it.dir,it.pink) }; car(c,x,y,heading,true); c.restore()
        rain(c); hud(c); controls(c)
    }
    private fun city(c:Canvas) {
        // water, blocks, and long reflective roads
        p.color=Color.rgb(8,33,59);c.drawRect(-1400f,-760f,1400f,-380f,p);c.drawRect(-1400f,390f,1400f,760f,p)
        p.color=Color.rgb(20,22,42);c.drawRect(-1400f,-380f,1400f,390f,p)
        for(i in -5..5) { p.color=if(i%2==0)Color.rgb(25,24,48) else Color.rgb(34,25,50);c.drawRect(i*240f-72,-360f,i*240f+72,360f,p); building(c,i*240f-42,-330f,62f,110f+(i+5)%3*48);building(c,i*240f+15,205f,82f,100f+(i+6)%4*36) }
        p.strokeWidth=106f;p.color=Color.rgb(10,13,25);c.drawLine(-1450f,-125f,1450f,-125f,p);c.drawLine(-1450f,155f,1450f,155f,p);c.drawLine(-110f,-700f,-110f,700f,p);c.drawLine(180f,-700f,180f,700f,p)
        p.strokeWidth=2f;p.color=Color.argb(120,255,216,120); for(i in -1400..1400 step 54){c.drawLine(i.toFloat(),-125f,i+26f,-125f,p);c.drawLine(i.toFloat(),155f,i+26f,155f,p)}
        p.color=Color.argb(80,57,187,255);for(i in -1350..1350 step 70)c.drawLine(i.toFloat(),-560f,i+30f,-470f,p)
    }
    private fun building(c:Canvas,bx:Float,by:Float,bw:Float,bh:Float) { p.color=Color.rgb(18,19,40);c.drawRect(bx,by,bx+bw,by+bh,p);p.color=Color.argb(160,104,211,255);var yy=by+13;while(yy<by+bh){var xx=bx+9;while(xx<bx+bw){c.drawRect(xx,yy,xx+6,yy+7,p);xx+=18};yy+=19} }
    private fun target(c:Canvas) { val pulse=42f+sin(now/180f)*8;glow.color=Color.argb(100,255,44,151);glow.maskFilter=BlurMaskFilter(22f,BlurMaskFilter.Blur.NORMAL);c.drawCircle(target.x,target.y,pulse,glow);glow.maskFilter=null;p.style=Paint.Style.STROKE;p.strokeWidth=4f;p.color=Color.rgb(255,83,171);c.drawCircle(target.x,target.y,pulse,p);p.style=Paint.Style.FILL;c.drawCircle(target.x,target.y,8f,p) }
    private fun car(c:Canvas,cx:Float,cy:Float,dir:Float,pink:Boolean) { c.save();c.translate(cx,cy);c.rotate(dir*180/Math.PI.toFloat()+90);glow.color=if(pink)Color.argb(120,255,25,151) else Color.argb(75,74,202,255);glow.maskFilter=BlurMaskFilter(18f,BlurMaskFilter.Blur.NORMAL);c.drawRoundRect(-17f,-31f,17f,31f,9f,9f,glow);glow.maskFilter=null;p.color=if(pink)Color.rgb(241,50,142) else Color.rgb(50,130,205);c.drawRoundRect(-15f,-28f,15f,28f,7f,7f,p);p.color=Color.rgb(184,239,255);c.drawRoundRect(-11f,-15f,11f,8f,4f,4f,p);p.color=Color.WHITE;c.drawRect(-13f,-30f,-7f,-25f,p);c.drawRect(7f,-30f,13f,-25f,p);c.restore() }
    private fun rain(c:Canvas){ p.strokeWidth=1.5f;p.color=Color.argb(75,180,220,255);rain.forEach { c.drawLine(it.x,it.y,it.x-7,it.y-it.len,p) } }
    private fun hud(c:Canvas){ val w=width.toFloat();p.color=Color.argb(190,5,8,22);c.drawRoundRect(22f,22f,286f,106f,18f,18f,p);label(c,"VICE NIGHTS",42f,54f,18f,Color.rgb(255,104,183));label(c,"COURIER RUN  •  $${score*500}",42f,82f,14f,Color.WHITE)
        val mx=w-160; p.color=Color.argb(185,5,8,22);c.drawRoundRect(mx,22f,w-22,160f,18f,18f,p);p.color=Color.rgb(24,32,56);c.drawRoundRect(mx+14,36f,w-36,146f,10f,10f,p);p.color=Color.rgb(45,80,108);c.drawLine(mx+20,94f,w-44,94f,p);c.drawLine(mx+70,42f,mx+70,140f,p);p.color=Color.rgb(255,74,164);c.drawCircle(mx+70+x/18,94+y/18,5f,p);p.color=Color.WHITE;c.drawCircle(mx+70,94f,4f,p)
        p.color=Color.argb(180,5,8,22);c.drawRoundRect(w/2-150,22f,w/2+150,66f,14f,14f,p);label(c,"DELIVERIES  ${score}/5     ${timeLeft.toInt()}s",w/2-124,51f,15f,Color.WHITE)
        p.color=Color.argb(150,255,77,164);c.drawRoundRect(w/2-142,59f,w/2-142+284*(timeLeft/99),63f,2f,2f,p)
        p.color=Color.argb(170,5,8,22);c.drawCircle(w-50,h-48,28f,p);label(c,if(paused)"▶" else "Ⅱ",w-59,h-39,24f,Color.WHITE)
        if(paused){p.color=Color.argb(190,4,6,18);c.drawRect(0f,0f,w,height.toFloat(),p);label(c,"PAUSED",w/2-72,h/2-8,28f,Color.WHITE);label(c,"Tap the top-right control to continue",w/2-150,h/2+24,14f,Color.LTGRAY)} }
    private fun controls(c:Canvas){val h=height.toFloat(); val r=66f; p.color=Color.argb(100,5,8,22);c.drawCircle(92f,h-88,r,p);p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.argb(170,255,255,255);c.drawCircle(92f,h-88,r,p);p.style=Paint.Style.FILL;p.color=Color.argb(if(gas)230 else 140,255,64,150);c.drawCircle(width-94f,h-88,r,p);label(c,"GAS",width-119f,h-82,16f,Color.WHITE);label(c,"◀        ▶",43f,h-81,15f,Color.WHITE)}
    private fun label(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int){p.typeface=Typeface.create("sans",Typeface.BOLD);p.textSize=size;p.color=color;p.setShadowLayer(8f,0f,1f,Color.argb(180,Color.red(color),Color.green(color),Color.blue(color)));c.drawText(s,x,y,p);p.clearShadowLayer()}
    override fun onTouchEvent(e:MotionEvent):Boolean { val h=height.toFloat();when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{ if(e.x>width-145&&e.y>h-145)gas=true else if(e.x<180&&e.y>h-180)steer=((e.x-92)/70).coerceIn(-1f,1f); else if(e.x>width-90&&e.y<100){paused=!paused;invalidate()} };MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{gas=false;steer=0f}};return true }
}
