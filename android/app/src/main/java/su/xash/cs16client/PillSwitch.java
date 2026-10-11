package su.xash.cs16client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Self-drawn pill switch. The framework Switch is restyled by Samsung
 * One UI into a tiny control with an unreadable OFF label on dark
 * backgrounds, so we draw our own: coral track when ON, visible dark
 * gray track when OFF, white thumb.
 */
public class PillSwitch extends View {
	public interface OnCheckedChangeListener {
		void onCheckedChanged( PillSwitch buttonView, boolean isChecked );
	}

	private final Paint trackPaint = new Paint( Paint.ANTI_ALIAS_FLAG );
	private final Paint thumbPaint = new Paint( Paint.ANTI_ALIAS_FLAG );
	private final RectF trackRect = new RectF();
	private boolean checked;
	private float progress;	// 0 = off, 1 = on (animated)
	private float density;
	private OnCheckedChangeListener listener;

	public PillSwitch( Context context ) {
		super( context );
		init();
	}

	public PillSwitch( Context context, AttributeSet attrs ) {
		super( context, attrs );
		init();
	}

	private void init() {
		density = getResources().getDisplayMetrics().density;
		trackPaint.setStyle( Paint.Style.FILL );
		thumbPaint.setStyle( Paint.Style.FILL );
		thumbPaint.setColor( 0xFFFFFFFF );
		setOnClickListener( new OnClickListener() {
			@Override
			public void onClick( View v ) {
				toggle();
			}
		});
	}

	public boolean isChecked() {
		return checked;
	}

	public void setChecked( boolean on ) {
		setChecked( on, true );
	}

	public void setChecked( final boolean on, boolean animate ) {
		if( checked == on )
			return;
		checked = on;
		if( listener != null )
			listener.onCheckedChanged( this, on );
		if( !animate ) {
			progress = on ? 1f : 0f;
			invalidate();
			return;
		}
		final long start = SystemClock.uptimeMillis();
		final float from = progress;
		final float to = on ? 1f : 0f;
		post( new Runnable() {
			@Override
			public void run() {
				float t = Math.min( 1f,
					( SystemClock.uptimeMillis() - start ) / 160f );
				progress = from + ( to - from ) * t;
				invalidate();
				if( t < 1f )
					post( this );
			}
		});
	}

	public void toggle() {
		setChecked( !checked );
	}

	public void setOnCheckedChangeListener( OnCheckedChangeListener l ) {
		listener = l;
	}

	@Override
	protected void onMeasure( int widthMeasureSpec, int heightMeasureSpec ) {
		int w = (int)( 56 * density + 0.5f );
		int h = (int)( 32 * density + 0.5f );
		setMeasuredDimension(
			resolveSize( w, widthMeasureSpec ),
			resolveSize( h, heightMeasureSpec ));
	}

	@Override
	protected void onDraw( Canvas canvas ) {
		int w = getWidth();
		int h = getHeight();
		if( w == 0 || h == 0 )
			return;
		float r = h / 2f;
		// track: visible dark gray OFF -> coral ON
		trackPaint.setColor( blend( 0xFF3A404D, 0xFFEA6C63, progress ));
		trackRect.set( 0, 0, w, h );
		canvas.drawRoundRect( trackRect, r, r, trackPaint );
		// thumb: white circle, slides left/right
		float pad = 3 * density;
		float thumbR = ( h - 2 * pad ) / 2f;
		float cx = pad + thumbR + ( w - 2 * pad - 2 * thumbR ) * progress;
		thumbPaint.setAlpha( (int)( 255 * ( 0.8f + 0.2f * progress )));
		canvas.drawCircle( cx, h / 2f, thumbR, thumbPaint );
	}

	private static int blend( int from, int to, float t ) {
		int fa = ( from >> 24 ) & 255, fr = ( from >> 16 ) & 255,
			fg = ( from >> 8 ) & 255, fb = from & 255;
		int ta = ( to >> 24 ) & 255, tr = ( to >> 16 ) & 255,
			tg = ( to >> 8 ) & 255, tb = to & 255;
		int a = fa + (int)(( ta - fa ) * t );
		int r = fr + (int)(( tr - fr ) * t );
		int g = fg + (int)(( tg - fg ) * t );
		int b = fb + (int)(( tb - fb ) * t );
		return ( a << 24 ) | ( r << 16 ) | ( g << 8 ) | b;
	}
}
