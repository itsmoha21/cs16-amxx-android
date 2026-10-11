package su.xash.cs16client;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Plain text editor for cstrike/addons/amxmodx/configs/plugins.ini and every
 * custom plugins-*.ini next to it. Search box to find any
 * plugins*.ini, line-number gutter, comment tinting, current line
 * highlighted, SAVE.
 */
public class PluginsActivity extends Activity {
	private static final int COMMENT_COLOR = 0xFF6E7A8C;
	private static final int ORANGE_COLOR = 0xFFDE8A3F;
	private static final int CARET_COLOR = 0xFFE8A612;

	private File configsDir;
	private File currentFile;
	private EditorText editor;
	private TextView gutter;
	private TextView fileName;
	private EditText search;
	private ListView results;
	private List<String> allNames;
	private boolean updating;
	private boolean loading;
	private boolean dirty;
	private String fileNameTag = "";

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_plugins );

		configsDir = new File( Environment.getExternalStorageDirectory(),
			"xash/cstrike/addons/amxmodx/configs" );
		((TextView) findViewById( R.id.plugins_path )).setText(
			configsDir.getAbsolutePath() );

		editor = (EditorText) findViewById( R.id.plugins_text );
		gutter = (TextView) findViewById( R.id.plugins_gutter );
		fileName = (TextView) findViewById( R.id.plugins_file_name );
		search = (EditText) findViewById( R.id.plugins_search );
		results = (ListView) findViewById( R.id.plugins_results );

		// no wrapping: long lines scroll sideways so the
		// gutter numbers stay lined up with the text rows
		editor.setHorizontallyScrolling( true );

		// the editor lives inside the screen scroll: claim vertical drags
		// so the editor scrolls its own text instead of being stuck.
		editor.setOnTouchListener( new View.OnTouchListener() {
			@Override
			public boolean onTouch( View v, android.view.MotionEvent event ) {
				v.getParent().requestDisallowInterceptTouchEvent( true );
				return false;
			}
		});

		// comment tinting + gutter follow the text; edits mark the file dirty
		editor.addTextChangedListener( new TextWatcher() {
			@Override
			public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
			}

			@Override
			public void onTextChanged( CharSequence s, int start, int before, int count ) {
			}

			@Override
			public void afterTextChanged( Editable s ) {
				tintComments( s );
				updateGutter();
				if( !loading && !dirty ) {
					dirty = true;
					updateSubtitle();
				}
			}
		});

		// caret moves -> highlight the current line number in the gutter
		editor.setOnCaretListener( new EditorText.OnCaretListener() {
			@Override
			public void onCaret( int pos ) {
				updateGutter();
			}
		});

		// keep the gutter lined up while the editor scrolls
		editor.getViewTreeObserver().addOnScrollChangedListener(
			new ViewTreeObserver.OnScrollChangedListener() {
			@Override
			public void onScrollChanged() {
				if( gutter != null )
					gutter.scrollTo( 0, editor.getScrollY() );
			}
		});

		// --- search: type to filter plugins*.ini, tap to open
		allNames = listPluginFiles();
		if( allNames.isEmpty() )
			Toast.makeText( this, R.string.plugins_empty, Toast.LENGTH_LONG ).show();

		results.setAdapter( new ArrayAdapter<String>( this,
			R.layout.script_item, new ArrayList<String>() ));
		// the list lives inside the screen scroll: claim vertical drags
		// so the results scroll themselves instead of the whole screen.
		results.setOnTouchListener( new View.OnTouchListener() {
			@Override
			public boolean onTouch( View v, android.view.MotionEvent event ) {
				v.getParent().requestDisallowInterceptTouchEvent( true );
				return false;
			}
		});
		results.setOnItemClickListener( new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick( AdapterView<?> parent, View view,
					int position, long id ) {
				String name = (String) parent.getItemAtPosition( position );
				hideResults();
				updating = true;
				search.setText( "" );
				updating = false;
				fileNameTag = name;
				updateSubtitle();
				loadFile( new File( configsDir, name ));
				search.clearFocus();
			}
		});

		search.addTextChangedListener( new TextWatcher() {
			@Override
			public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
			}

			@Override
			public void onTextChanged( CharSequence s, int start, int before, int count ) {
			}

			@Override
			public void afterTextChanged( Editable s ) {
				if( updating )
					return;
				showResults( filter( s.toString()));
			}
		});
		search.setOnFocusChangeListener( new View.OnFocusChangeListener() {
			@Override
			public void onFocusChange( View v, boolean hasFocus ) {
				if( hasFocus )
					showResults( filter( search.getText().toString()));
				else
					hideResults();
			}
		});
		// IME action "search" = jump to the first match
		search.setOnEditorActionListener( new TextView.OnEditorActionListener() {
			@Override
			public boolean onEditorAction( TextView v, int actionId, KeyEvent event ) {
				if( results.getAdapter() != null
						&& results.getAdapter().getCount() > 0 ) {
					String name = (String) results.getAdapter().getItem( 0 );
					hideResults();
					updating = true;
					search.setText( "" );
					updating = false;
					fileNameTag = name;
					updateSubtitle();
					loadFile( new File( configsDir, name ));
					search.clearFocus();
				}
				return true;
			}
		});

		findViewById( R.id.plugins_save ).setOnClickListener(
			new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				saveFile();
			}
		});

		// shared bottom tab bar: Plugins tab is inert here, the
		// other tabs switch screens without stacking.
		TabBar.init( this, TabBar.TAB_PLUGINS );

		if( !allNames.isEmpty() ) {
			fileNameTag = allNames.get( 0 );
			updateSubtitle();
			loadFile( new File( configsDir, allNames.get( 0 )));
		}
	}

	/** Case-insensitive substring filter over the plugins*.ini names. */
	private List<String> filter( String query ) {
		String q = query == null ? "" : query.trim().toLowerCase( Locale.US );
		if( q.isEmpty() )
			return allNames;
		List<String> out = new ArrayList<String>();
		for( String n : allNames ) {
			if( n.toLowerCase( Locale.US ).contains( q ))
				out.add( n );
		}
		return out;
	}

	private void showResults( List<String> names ) {
		@SuppressWarnings("unchecked")
		ArrayAdapter<String> adapter =
			(ArrayAdapter<String>) results.getAdapter();
		adapter.clear();
		adapter.addAll( names );
		adapter.notifyDataSetChanged();
		findViewById( R.id.plugins_results_box ).setVisibility(
			names.isEmpty() ? View.GONE : View.VISIBLE );
		results.setVisibility( View.VISIBLE );
	}

	private void hideResults() {
		findViewById( R.id.plugins_results_box ).setVisibility( View.GONE );
	}

	private void updateSubtitle() {
		if( fileName == null )
			return;
		if( dirty ) {
			fileName.setText( fileNameTag + "  \u00b7  edited" );
			fileName.setTextColor( CARET_COLOR );
		} else {
			fileName.setText( fileNameTag );
			fileName.setTextColor( 0xFF7E8CA0 );
		}
	}

	private List<String> listPluginFiles() {
		List<String> out = new ArrayList<String>();
		File[] files = configsDir.listFiles();
		if( files != null ) {
			for( File f : files ) {
				String n = f.getName().toLowerCase();
				if( f.isFile() && n.startsWith( "plugins" ) && n.endsWith( ".ini" ))
					out.add( f.getName() );
			}
		}
		Collections.sort( out, new Comparator<String>() {
			@Override
			public int compare( String a, String b ) {
				// plugins.ini first, then alphabetical
				if( a.equalsIgnoreCase( "plugins.ini" ))
					return -1;
				if( b.equalsIgnoreCase( "plugins.ini" ))
					return 1;
				return a.compareToIgnoreCase( b );
			}
		});
		return out;
	}

	private void loadFile( File f ) {
		currentFile = f;
		StringBuilder sb = new StringBuilder();
		try {
			BufferedReader br = new BufferedReader( new FileReader( f ));
			String line;
			while(( line = br.readLine()) != null ) {
				sb.append( line ).append( '\n' );
			}
			br.close();
		} catch( Throwable t ) {
			Toast.makeText( this, "Read error: " + t.getMessage(),
				Toast.LENGTH_LONG ).show();
		}
		loading = true;
		editor.setText( sb.toString() );
		loading = false;
		editor.setSelection( 0 );
		dirty = false;
		updateSubtitle();
	}

	/**
	 * Tinting: full comment lines are gray italic, inline
	 * "; tail" comments too, and inside comments path-like tokens (a/b/c)
	 * and ALL-CAPS words get the orange accent.
	 */
	private void tintComments( Editable s ) {
		// drop our previous spans (span edits do not re-trigger
		// afterTextChanged, so no loop here)
		ForegroundColorSpan[] colors = s.getSpans( 0, s.length(),
			ForegroundColorSpan.class );
		for( ForegroundColorSpan sp : colors ) {
			int c = sp.getForegroundColor();
			if( c == COMMENT_COLOR || c == ORANGE_COLOR )
				s.removeSpan( sp );
		}
		StyleSpan[] styles = s.getSpans( 0, s.length(), StyleSpan.class );
		for( StyleSpan sp : styles ) {
			if( sp.getStyle() == Typeface.ITALIC )
				s.removeSpan( sp );
		}

		String str = s.toString();
		int n = str.length();
		int i = 0;
		while( i < n ) {
			int j = str.indexOf( '\n', i );
			if( j < 0 )
				j = n;
			int k = i;
			while( k < j && ( str.charAt( k ) == ' ' || str.charAt( k ) == '\t' ))
				k++;
			if( k < j && str.charAt( k ) == ';' ) {
				// full comment line
				tintRange( s, i, j, COMMENT_COLOR, true );
				tintAccentTokens( s, k + 1, j );
			} else {
				// inline "; ..." tail comment (semicolon after text)
				int p = k;
				while( p < j && str.charAt( p ) != ';' )
					p++;
				if( p < j && p > i && ( str.charAt( p - 1 ) == ' '
					|| str.charAt( p - 1 ) == '\t' ))
					tintRange( s, p, j, COMMENT_COLOR, true );
			}
			i = j + 1;
		}
	}

	private void tintRange( Editable s, int start, int end, int color,
			boolean italic ) {
		if( start >= end )
			return;
		s.setSpan( new ForegroundColorSpan( color ), start, end,
			Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
		if( italic )
			s.setSpan( new StyleSpan( Typeface.ITALIC ), start, end,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
	}

	/** Orange accent for a/b path tokens and ALL-CAPS words in comments. */
	private void tintAccentTokens( Editable s, int start, int end ) {
		String str = s.toString().substring( start, end );
		int i = 0;
		int m = str.length();
		while( i < m ) {
			while( i < m && ( !Character.isLetterOrDigit( str.charAt( i ))
				&& str.charAt( i ) != '_' && str.charAt( i ) != '/' ))
				i++;
			int j = i;
			while( j < m && ( Character.isLetterOrDigit( str.charAt( j ))
				|| str.charAt( j ) == '_' || str.charAt( j ) == '/' ))
				j++;
			if( j > i ) {
				String tok = str.substring( i, j );
				boolean slash = tok.indexOf( '/' ) >= 0;
				boolean caps = tok.length() >= 2 && tok.equals( tok.toUpperCase() )
					&& tok.indexOf( '/' ) < 0;
				if( slash || caps )
					s.setSpan( new ForegroundColorSpan( ORANGE_COLOR ),
						start + i, start + j,
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
				i = j;
			} else {
				i++;
			}
		}
	}

	/**
	 * Gutter line numbers + gold caret line; rebuilt together and the
	 * scroll offset restored right after, so it stays lined up.
	 */
	private void updateGutter() {
		if( gutter == null || editor == null )
			return;
		editor.post( new Runnable() {
			@Override
			public void run() {
				int lines = editor.getLineCount();
				if( lines < 1 )
					lines = 1;
				StringBuilder sb = new StringBuilder();
				for( int i = 1; i <= lines; i++ )
					sb.append( i ).append( '\n' );
				SpannableStringBuilder buf =
					new SpannableStringBuilder( sb.toString() );
				// gold the number of the caret's line
				int caret = editor.getSelectionStart();
				if( caret >= 0 ) {
					String text = editor.getText().toString();
					int line = 0;
					for( int i = 0; i < caret && i < text.length(); i++ ) {
						if( text.charAt( i ) == '\n' )
							line++;
					}
					int start = 0;
					for( int i = 0; i < line; i++ ) {
						start = buf.toString().indexOf( '\n', start );
						if( start < 0 )
							break;
						start++;
					}
					int end = buf.toString().indexOf( '\n', start );
					if( end < 0 )
						end = buf.length();
					if( start < end && start >= 0 )
						buf.setSpan( new ForegroundColorSpan( CARET_COLOR ),
							start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE );
				}
				gutter.setText( buf );
				gutter.scrollTo( 0, editor.getScrollY() );
			}
		});
	}

	private void saveFile() {
		if( currentFile == null )
			return;
		try {
			PrintWriter pw = new PrintWriter( new FileWriter( currentFile, false ));
			pw.print( editor.getText().toString() );
			pw.close();
			dirty = false;
			updateSubtitle();
			Toast.makeText( this, R.string.plugins_saved, Toast.LENGTH_SHORT ).show();
		} catch( Throwable t ) {
			Toast.makeText( this, "Write error: " + t.getMessage(),
				Toast.LENGTH_LONG ).show();
		}
	}
}
