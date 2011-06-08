package com.dozingcatsoftware.bouncy;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceView;

import com.dozingcatsoftware.bouncy.elements.FieldElement;
import com.dozingcatsoftware.bouncy.util.GLVertexList;
import com.dozingcatsoftware.bouncy.util.GLVertexListManager;

/** Draws the game field. Field elements are defined in world coordinates, which this view transforms to screen/pixel coordinates.
 * @author brian
 */
public class FieldView extends GLSurfaceView implements IFieldRenderer, GLSurfaceView.Renderer {

	public FieldView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setRenderer(this);
		setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
	}
	
	boolean showFPS;
	boolean highQuality;
	boolean independentFlippers;
	
	Field field;
	
	Paint paint = new Paint(); {paint.setAntiAlias(true);}
	Paint textPaint = new Paint(); {textPaint.setARGB(255, 255, 255, 255);}
	float zoom = 1.0f;
	Canvas canvas;
	
	// x/y offsets and scale are cached at the beginning of draw(), to avoid repeated calls as elements are drawn.
	// They shouldn't change between calls to draw() either, but better safe than sorry. 
	float cachedXOffset, cachedYOffset, cachedScale, cachedHeight;
	
	String debugMessage;
	double fps;
	
	public void setField(Field value) {
		field = value;
	}
	
	public void setDebugMessage(String value) {
		debugMessage = value;
	}
	
	public void setFPS(double fps) {
		this.fps = fps;
	}
	
	public void setShowFPS(boolean value) {
		showFPS = value;
	}
	
	public void setIndependentFlippers(boolean value) {
		independentFlippers = value;
	}
	
	public void setHighQuality(boolean value) {
		highQuality = value;
	}
	public boolean isHighQuality() {
		return highQuality;
	}
	
	float getScale() {
		float xs = this.getWidth() / field.getWidth();
		float ys = this.getHeight() / field.getHeight();
		return Math.min(xs, ys) * this.zoom;
	}
	
	float getXOffset() {
		float scale = this.getScale();
		return (this.getWidth() - scale*field.getWidth()) / 2.0f;
	}
	float getYOffset() {
		float scale = this.getScale();
		return (this.getHeight() - scale*field.getHeight()) / 2.0f;
	}
	
	public float getZoom() {
		return zoom;
	}
	public void setZoom(float value) {
		zoom = value;
	}
	
	/** Saves scale and x and y offsets for use by world2pixel methods, avoiding repeated method calls and math operations. */
	void cacheScaleAndOffsets() {
		cachedXOffset = getXOffset();
		cachedYOffset = getYOffset();
		cachedScale = getScale();
		cachedHeight = getHeight();
	}
	
	// world2pixel methods assume cacheScaleAndOffsets has been called previously
	/** Converts an x coordinate from world coordinates to the view's pixel coordinates. 
	 */
	float world2pixelX(float x) {
		return x * cachedScale + cachedXOffset;
		//return x * getScale() + getXOffset();
	}
	
	/** Converts an x coordinate from world coordinates to the view's pixel coordinates. In world coordinates, positive y is up,
	 * in pixel coordinates, positive y is down. 
	 */
	float world2pixelY(float y) {
		return cachedHeight - (y * cachedScale) - cachedYOffset;
		//return getHeight() - (y * getScale()) - getYOffset();
	}
	
	// for compatibility with Android 1.6, use reflection for multitouch features
	boolean hasMultitouch;
	Method getPointerCountMethod;
	Method getXMethod;
	int MOTIONEVENT_ACTION_MASK = 0xffffffff; // defaults to no-op AND mask
	int MOTIONEVENT_ACTION_POINTER_UP;
	int MOTIONEVENT_ACTION_POINTER_INDEX_MASK;
	int MOTIONEVENT_ACTION_POINTER_INDEX_SHIFT;
	
	{
		try {
			getPointerCountMethod = MotionEvent.class.getMethod("getPointerCount");
			getXMethod = MotionEvent.class.getMethod("getX", int.class);
			MOTIONEVENT_ACTION_MASK = MotionEvent.class.getField("ACTION_MASK").getInt(null);
			MOTIONEVENT_ACTION_POINTER_UP = MotionEvent.class.getField("ACTION_POINTER_UP").getInt(null);
			MOTIONEVENT_ACTION_POINTER_INDEX_MASK = MotionEvent.class.getField("ACTION_POINTER_INDEX_MASK").getInt(null);
			MOTIONEVENT_ACTION_POINTER_INDEX_SHIFT = MotionEvent.class.getField("ACTION_POINTER_INDEX_SHIFT").getInt(null);
			hasMultitouch = true;
		}
		catch(Exception ex) {
			hasMultitouch = false;
		}
	}
	

    /** Called when the view is touched. Activates flippers, starts a new game if one is not in progress, and
     * launches a ball if one is not in play.
     */
	@Override
    public boolean onTouchEvent(MotionEvent event) {
		int actionType = event.getAction() & MOTIONEVENT_ACTION_MASK;
    	synchronized(field) {
        	if (actionType==MotionEvent.ACTION_DOWN) {
        		// start game if not in progress
        		if (!field.getGameState().isGameInProgress()) {
        			field.resetForLevel(this.getContext(), 1);
        			field.startGame();
        		}
            	// remove "dead" balls and launch if none already in play
        		field.handleDeadBalls();
        		if (field.getBalls().size()==0) field.launchBall();
        	}
        	// activate or deactivate flippers
        	if (this.independentFlippers && this.hasMultitouch) {
        		try {
        			// determine whether to activate left and/or right flippers (using reflection for Android 2.2 multitouch APIs)
            		boolean left=false, right=false;
            		if (actionType!=MotionEvent.ACTION_UP) {
            			int npointers = (Integer)getPointerCountMethod.invoke(event);
            			// if pointer was lifted (ACTION_POINTER_UP), get its index so we don't count it as pressed
            			int liftedPointerIndex = -1;
            			if (actionType==MOTIONEVENT_ACTION_POINTER_UP){
            				liftedPointerIndex = (event.getAction() & MOTIONEVENT_ACTION_POINTER_INDEX_MASK) >> MOTIONEVENT_ACTION_POINTER_INDEX_SHIFT;
            				//this.setDebugMessage("Lifted " + liftedPointerIndex);
            			}
            			//this.setDebugMessage("Pointers: " + npointers);
            			float halfwidth = this.getWidth() / 2;
            			for(int i=0; i<npointers; i++) {
            				if (i!=liftedPointerIndex) {
                				float touchx = (Float)getXMethod.invoke(event, i);
                				if (touchx < halfwidth) left = true;
                				else right = true;
            				}
            			}
            		}
            		field.setLeftFlippersEngaged(left);
            		field.setRightFlippersEngaged(right);
        		}
        		catch(Exception ignored) {}
        	}
        	else {
            	boolean flipperState = !(event.getAction()==MotionEvent.ACTION_UP);
            	field.setAllFlippersEngaged(flipperState);
        	}
    	}
    	return true;
    }
	
	// GL implementation start
	GLVertexListManager vertexListManager = new GLVertexListManager();
	GLVertexList lineVertexList;
	
	
	static float[] SIN_VALUES = new float[12];
	static float[] COS_VALUES = new float[12];
	static {
		for(int i=0; i<SIN_VALUES.length; i++) {
			double angle = 2*Math.PI * i / SIN_VALUES.length;
			SIN_VALUES[i] = (float)Math.sin(angle);
			COS_VALUES[i] = (float)Math.cos(angle);
		}
	}
	
	void startGLElements(GL10 gl) {
		vertexListManager.begin();
		lineVertexList = vertexListManager.addVertexListForMode(GL10.GL_LINES);
	}
	
	void endGLElements(GL10 gl) {
		vertexListManager.end();
		
        gl.glEnable(GL10.GL_DITHER);        
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
        gl.glMatrixMode(GL10.GL_MODELVIEW); 
        gl.glLoadIdentity();
        
        gl.glLineWidth(this.isHighQuality() ? 2 : 1);
        
        vertexListManager.render(gl);
	}

	// Implementation of IFieldRenderer drawing methods that FieldElement classes can call. Assumes cacheScaleAndOffsets has been called.
	@Override
	public void drawLine(float x1, float y1, float x2, float y2, int r, int g, int b) {
		drawLineGL(x1, y1, x2, y2, r, g, b);
	}
	
	void drawLineGL(float x1, float y1, float x2, float y2, int r, int g, int b) {
		lineVertexList.addVertex(world2pixelX(x1), world2pixelY(y1));
		lineVertexList.addVertex(world2pixelX(x2), world2pixelY(y2));

		float rf = r/255f;
		float gf = g/255f;
		float bf = b/255f;
		lineVertexList.addColor(rf, gf, bf);
		lineVertexList.addColor(rf, gf, bf);
	}
	
	@Override
	public void fillCircle(float cx, float cy, float radius, int r, int g, int b) {
		fillCircleGL(cx, cy, radius, r, g, b);
	}
	
	void fillCircleGL(float cx, float cy, float radius, int r, int g, int b) {
		GLVertexList circleVertexList = vertexListManager.addVertexListForMode(GL10.GL_TRIANGLE_FAN);
		circleVertexList.addColor(r/255f, g/255f, b/255f);

		for(int i=0; i<SIN_VALUES.length; i++) {
			float x = cx + radius*COS_VALUES[i];
			float y = cy + radius*SIN_VALUES[i];
			circleVertexList.addVertex(world2pixelX(x), world2pixelY(y));
		}
	}
	
	@Override
	public void frameCircle(float cx, float cy, float radius, int r, int g, int b) {
		frameCircleGL(cx, cy, radius, r, g, b);
	}
	
	void frameCircleGL(float cx, float cy, float radius, int r, int g, int b) {
		GLVertexList circleVertexList = vertexListManager.addVertexListForMode(GL10.GL_LINE_LOOP);
		circleVertexList.addColor(r/255f, g/255f, b/255f);

		for(int i=0; i<SIN_VALUES.length; i++) {
			float x = cx + radius*COS_VALUES[i];
			float y = cy + radius*SIN_VALUES[i];
			circleVertexList.addVertex(world2pixelX(x), world2pixelY(y));
		}
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		if (field==null) return;
		synchronized(field) {
			cacheScaleAndOffsets();

			startGLElements(gl);
			
			for(FieldElement element : field.getFieldElementsArray()) {
				element.draw(this);
			}

			field.drawBalls(this);

			if (this.showFPS) {
				//if (debugMessage!=null) {
					int val = (int)fps;
					int sx = 1;
					int sy = 1;
					int w = 3;
					int gap = 1;
					
					int tens = val/10;
					for(int i=0, y=sy; i<tens; i++, y+=gap) {
						drawLine(sx, y, sx+w, y, 255, 0, 0);
					}
					int ones = val%10;
					sx += w+gap;
					for(int i=0, y=sy; i<ones; i++, y+=gap) {
						drawLine(sx, y, sx+w, y, 255, 0, 0);
					}
				//}
			}

			endGLElements(gl);
		}
	}


	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
	    gl.glViewport(0, 0, width, height);
    }

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
	    gl.glClearColor(0.0f, 0.0f, 0.0f, 1);
	    gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_FASTEST);
	    gl.glShadeModel(GL10.GL_FLAT);
	    gl.glDisable(GL10.GL_DEPTH_TEST);
	    gl.glEnable(GL10.GL_BLEND);
	    gl.glBlendFunc(GL10.GL_ONE, GL10.GL_ONE_MINUS_SRC_ALPHA); 

	    gl.glViewport(0, 0, getWidth(), getHeight());
	    gl.glMatrixMode(GL10.GL_PROJECTION);
	    gl.glLoadIdentity();
	    gl.glEnable(GL10.GL_BLEND);
	    gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
	    gl.glShadeModel(GL10.GL_FLAT);
	    gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY);

	    GLU.gluOrtho2D(gl, 0, getWidth(), getHeight(), 0);
	    //GLU.gluPerspective(gl, 45.0f, (float)getWidth() / (float)getHeight(), 0.1f, 100f);
	}

	
    protected static FloatBuffer makeFloatBuffer(float[] arr) {
        ByteBuffer vbb = ByteBuffer.allocateDirect(arr.length * 4); 
        vbb.order(ByteOrder.nativeOrder());
        FloatBuffer texBuffer = vbb.asFloatBuffer();
        texBuffer.put(arr);
        texBuffer.position(0);
        return texBuffer; 
    }

    protected static IntBuffer makeIntBuffer(int[] arr) {
        ByteBuffer vbb = ByteBuffer.allocateDirect(arr.length * 4); 
        vbb.order(ByteOrder.nativeOrder());
        IntBuffer texBuffer = vbb.asIntBuffer();
        texBuffer.put(arr);
        texBuffer.position(0);
        return texBuffer; 
    }
    
    // GL implementation end, previous Canvas implementation here

	/** Main draw method for Canvas, formerly called from FieldDriver's game thread. 
	 * Calls each FieldElement's draw() method passing itself as the IFieldRenderer implementation.
	 */
	public void doDraw(Canvas c) {
		cacheScaleAndOffsets();
		paint.setStrokeWidth(this.highQuality ? 2 : 0);
		// call draw() on each FieldElement, draw balls separately
		this.canvas = c;
		
		for(FieldElement element : field.getFieldElementsArray()) {
			element.draw(this);
		}

		field.drawBalls(this);
		
		if (this.showFPS) {
			if (debugMessage!=null) {
				c.drawText(""+debugMessage, 10, 10, textPaint);
			}
		}
	}
	
	void frameCircleCanvas(float cx, float cy, float radius, int r, int g, int b) {
		this.paint.setARGB(255, r, g, b);
		this.paint.setStyle(Paint.Style.STROKE);
		float rad = radius * cachedScale;
		this.canvas.drawCircle(world2pixelX(cx), world2pixelY(cy), rad, paint);
	}
	
	void fillCircleCanvas(float cx, float cy, float radius, int r, int g, int b) {
		this.paint.setARGB(255, r, g, b);
		this.paint.setStyle(Paint.Style.FILL);
		float rad = radius * cachedScale;
		this.canvas.drawCircle(world2pixelX(cx), world2pixelY(cy), rad, paint);
	}
	
	void drawLineCanvas(float x1, float y1, float x2, float y2, int r, int g, int b) {
		this.paint.setARGB(255, r, g, b);
		this.canvas.drawLine(world2pixelX(x1), world2pixelY(y1), world2pixelX(x2), world2pixelY(y2), this.paint);
	}
	
}
