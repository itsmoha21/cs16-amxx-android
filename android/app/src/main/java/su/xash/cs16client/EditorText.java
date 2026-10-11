package su.xash.cs16client;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

/**
 * EditText that reports caret movement, so the plugins editor can keep
 * the current line number in the gutter highlighted.
 */
public class EditorText extends EditText {
	public interface OnCaretListener {
		void onCaret( int pos );
	}

	private OnCaretListener caretListener;

	public EditorText( Context context ) {
		super( context );
	}

	public EditorText( Context context, AttributeSet attrs ) {
		super( context, attrs );
	}

	public EditorText( Context context, AttributeSet attrs, int defStyleAttr ) {
		super( context, attrs, defStyleAttr );
	}

	public void setOnCaretListener( OnCaretListener l ) {
		caretListener = l;
	}

	@Override
	protected void onSelectionChanged( int start, int end ) {
		super.onSelectionChanged( start, end );
		if( caretListener != null )
			caretListener.onCaret( start );
	}
}
