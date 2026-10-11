package su.xash.cs16client;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-app SMA -&gt; AMXX compiler (amxxpc): multi-select .sma
 * list, full log. Paths are automatic - no folder picker.
 *
 * Lists *.sma scripts from &lt;xash&gt;/cstrike/addons/amxmodx/scripting,
 * compiles with the bundled amxxpc binary (this app's
 * nativeLibraryDir, packaged as libamxxpc.so) against
 * scripting/include, writes the result into addons/amxmodx/plugins
 * and shows the full log.
 */
public class CompilerActivity extends Activity {
        private TextView logView;
        private android.widget.ScrollView logScroll;
        private Button compileSelButton;
        private Button compileAllButton;
        private ListView list;
        private List<File> scripts = new ArrayList<File>();
        private File scriptsDir;
        private File outputDir;

        @Override
        protected void onCreate( Bundle savedInstanceState ) {
                super.onCreate( savedInstanceState );
                setContentView( R.layout.activity_compiler );

                scriptsDir = defaultScriptsDir();
                outputDir = defaultOutputDir();

                list = (ListView) findViewById( R.id.compiler_scripts );
                refreshScripts();

                logView = (TextView) findViewById( R.id.compiler_log );
                logView.setTypeface( Typeface.MONOSPACE );
                // NOTE: no setTextIsSelectable here - a selectable TextView
                // consumes drag gestures for text selection, which freezes
                // manual scrolling of the log. COPY button covers copying.
                logScroll = (android.widget.ScrollView) findViewById( R.id.compiler_log_scroll );
                // the log scroll lives inside the screen scroll: claim vertical
                // drags for the log so manual scrolling actually moves it.
                logScroll.setOnTouchListener( new View.OnTouchListener() {
                        @Override
                        public boolean onTouch( View v, android.view.MotionEvent event ) {
                                v.getParent().requestDisallowInterceptTouchEvent( true );
                                return false;
                        }
                });

                compileSelButton = (Button) findViewById( R.id.compiler_go );
                compileAllButton = (Button) findViewById( R.id.compiler_go_all );

                compileSelButton.setOnClickListener( new View.OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                                compileSelected();
                        }
                });
                compileAllButton.setOnClickListener( new View.OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                                compileAll( scriptsDir );
                        }
                });

                // shared bottom tab bar: Compile tab is inert here, the
                // other tabs switch screens without stacking.
                TabBar.init( this, TabBar.TAB_COMPILE );
                ((Button) findViewById( R.id.compiler_copy )).setOnClickListener(
                                new View.OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                                copyLog();
                        }
                });
                ((Button) findViewById( R.id.compiler_clear )).setOnClickListener(
                                new View.OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                                logView.setText( "" );
                        }
                });
        }

        private File getXashDir() {
                return new File( Environment.getExternalStorageDirectory(), "xash" );
        }

        private File defaultScriptsDir() {
                return new File( getXashDir(), "cstrike/addons/amxmodx/scripting" );
        }

        private File defaultOutputDir() {
                return new File( getXashDir(), "cstrike/addons/amxmodx/plugins" );
        }

        /** (Re)fill the scripts list from scriptsDir, keeping checked names. */
        private void refreshScripts() {
                List<String> checked = checkedNames();
                scripts = listScripts( scriptsDir );
                List<String> names = new ArrayList<String>();
                for( File f : scripts )
                        names.add( f.getName() );

                list.setAdapter( new ArrayAdapter<String>( this,
                        R.layout.script_item_check, names ));
                // the list lives inside the screen scroll: claim
                // vertical drags so the list scrolls itself instead
                // of being stuck behind the outer ScrollView.
                list.setOnTouchListener( new View.OnTouchListener() {
                        @Override
                        public boolean onTouch( View v, android.view.MotionEvent event ) {
                                v.getParent().requestDisallowInterceptTouchEvent( true );
                                return false;
                        }
                });
                list.setOnItemClickListener( new AdapterView.OnItemClickListener() {
                        @Override
                        public void onItemClick( AdapterView<?> parent, View view,
                                        int position, long id ) {
                                // CHOICE_MODE_MULTIPLE already toggled the
                                // check state before this callback - just
                                // refresh the target label.
                                updateSelectedLabel();
                        }
                });

                if( scripts.isEmpty() ) {
                        appendLog( getString( R.string.compiler_no_scripts )
                                + " (" + scriptsDir.getAbsolutePath() + ")\n" );
                } else {
                        // restore previous selection (or preselect the first)
                        for( int i = 0; i < scripts.size(); i++ ) {
                                if( i == 0 && checked.isEmpty() && outputDir.equals(
                                        defaultOutputDir()))
                                        list.setItemChecked( i, true );
                                else if( checked.contains( scripts.get( i ).getName()))
                                        list.setItemChecked( i, true );
                        }
                }
                updateSelectedLabel();
        }

        private List<String> checkedNames() {
                List<String> out = new ArrayList<String>();
                if( list == null )
                        return out;
                android.util.SparseBooleanArray checked =
                        list.getCheckedItemPositions();
                for( int i = 0; i < scripts.size(); i++ ) {
                        if( checked.get( i ))
                                out.add( scripts.get( i ).getName());
                }
                return out;
        }

        private List<File> checkedScripts() {
                List<File> out = new ArrayList<File>();
                android.util.SparseBooleanArray checked =
                        list.getCheckedItemPositions();
                for( int i = 0; i < scripts.size(); i++ ) {
                        if( checked.get( i ))
                                out.add( scripts.get( i ));
                }
                return out;
        }

        private void updateSelectedLabel() {
                TextView label = (TextView) findViewById( R.id.compiler_selected );
                if( label == null )
                        return;
                List<File> sel = checkedScripts();
                if( sel.isEmpty() ) {
                        label.setText( R.string.compiler_none_checked );
                        return;
                }
                String amxx = sel.size() == 1
                        ? sel.get( 0 ).getName().replaceAll( "(?i)\\.sma$", "" ) + ".amxx"
                        : sel.size() + " plugins";
                label.setText( getString( R.string.compiler_selected,
                        sel.get( 0 ).getName(), amxx ));
        }

        private List<File> listScripts( File dir ) {
                List<File> out = new ArrayList<File>();
                File[] files = dir.listFiles();
                if( files != null ) {
                        for( File f : files ) {
                                if( f.isFile() && f.getName().toLowerCase().endsWith( ".sma" ))
                                        out.add( f );
                        }
                }
                Collections.sort( out, new Comparator<File>() {
                        @Override
                        public int compare( File a, File b ) {
                                return a.getName().compareToIgnoreCase( b.getName() );
                        }
                });
                return out;
        }

        private File findAmxxpc() {
                File amxxpc = new File( getApplicationInfo().nativeLibraryDir, "libamxxpc.so" );
                return amxxpc.isFile() ? amxxpc : null;
        }

        private void setBusy( final boolean busy ) {
                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                compileSelButton.setEnabled( !busy );
                                compileAllButton.setEnabled( !busy );
                        }
                });
        }

        /** Blocking single-script compile with default options; returns amxxpc exit code. */
        private int compileOne( File amxxpc, File includeDir, File script ) {
                if( !outputDir.isDirectory() )
                        outputDir.mkdirs();

                String outName = script.getName().replaceAll( "(?i)\\.sma$", "" ) + ".amxx";
                File out = new File( outputDir, outName );

                appendLog( ">>> amxxpc " + script.getName() + "\n" );

                List<String> args = new ArrayList<String>();
                args.add( amxxpc.getAbsolutePath() );
                args.add( "-i" + includeDir.getAbsolutePath() );
                args.add( "-o" + out.getAbsolutePath() );
                args.add( script.getAbsolutePath() );

                appendLog( "cmd: " + join( args, " " ) + "\n" );

                try {
                        ProcessBuilder pb = new ProcessBuilder( args );
                        pb.redirectErrorStream( true );
                        Process proc = pb.start();
                        BufferedReader stdout = new BufferedReader(
                                new InputStreamReader( proc.getInputStream()));
                        String line;
                        while(( line = stdout.readLine()) != null )
                                appendLog( line + "\n" );
                        int code = proc.waitFor();
                        // old amxxpc builds exited 0 even on fatal compile
                        // errors and could leave a header-only stub .amxx
                        // (~36 bytes). Only report OK when a plausible
                        // plugin file actually exists.
                        if( code == 0 && ( !out.isFile() || out.length() < 256L )) {
                                out.delete();
                                appendLog( "=== FAILED: " + script.getName()
                                        + " (no valid plugin produced)\n" );
                                return 1;
                        }
                        if( code == 0 )
                                appendLog( "=== OK: " + out.getName()
                                        + " (" + out.length() + " bytes)\n" );
                        else
                                appendLog( "=== FAILED: " + script.getName()
                                        + " (exit code " + code + ")\n" );
                        return code;
                } catch( Throwable t ) {
                        appendLog( "! " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n" );
                        return 1;
                }
        }

        private void compileSelected() {
                final List<File> selected = checkedScripts();
                if( selected.isEmpty() ) {
                        Toast.makeText( this, R.string.compiler_none_checked,
                                Toast.LENGTH_SHORT ).show();
                        return;
                }

                final File amxxpc = findAmxxpc();
                if( amxxpc == null ) {
                        appendLog( "! compiler binary not found in nativeLibraryDir\n" );
                        return;
                }

                // includes: scripting/include that ships with the addons
                File inc = new File( scriptsDir, "include" );
                final File includeDir = inc.isDirectory() ? inc
                        : new File( defaultScriptsDir(), "include" );
                setBusy( true );

                new Thread( new Runnable() {
                        @Override
                        public void run() {
                                try {
                                        for( File script : selected )
                                                compileOne( amxxpc, includeDir, script );
                                } finally {
                                        setBusy( false );
                                        showDone( selected.size() + " script(s)" );
                                }
                        }
                }).start();
        }

        private void compileAll( final File dir ) {
                final File amxxpc = findAmxxpc();
                if( amxxpc == null ) {
                        appendLog( "! compiler binary not found in nativeLibraryDir\n" );
                        return;
                }

                File inc = new File( dir, "include" );
                final File includeDir = inc.isDirectory() ? inc
                        : new File( defaultScriptsDir(), "include" );
                setBusy( true );
                appendLog( ">>> compiling ALL scripts...\n" );

                new Thread( new Runnable() {
                        @Override
                        public void run() {
                                int ok = 0;
                                int failed = 0;
                                int total = 0;
                                try {
                                        for( File script : listScripts( dir )) {
                                                total++;
                                                try {
                                                        if( compileOne( amxxpc, includeDir, script ) == 0 )
                                                                ok++;
                                                        else
                                                                failed++;
                                                } catch( Throwable t ) {
                                                        failed++;
                                                        appendLog( "! " + script.getName() + ": "
                                                                + t.getClass().getSimpleName() + ": "
                                                                + t.getMessage() + "\n" );
                                                }
                                        }
                                        appendLog( "=== DONE: " + ok + " succeeded, " + failed
                                                + " failed (of " + total + ")\n" );
                                } finally {
                                        setBusy( false );
                                        showDone( ok + "/" + total );
                                }
                        }
                }).start();
        }

        private void showDone( final String detail ) {
                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                String msg = getString( R.string.compiler_done );
                                if( detail != null )
                                        msg += ": " + detail;
                                Toast.makeText( CompilerActivity.this, msg, Toast.LENGTH_SHORT ).show();
                        }
                });
        }

        private void appendLog( final String text ) {
                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                // follow the output only while the user is
                                // already at the bottom; a scrolled-up user
                                // keeps reading without being yanked down.
                                boolean follow = true;
                                if( logScroll != null && logScroll.getChildCount() > 0 ) {
                                        android.view.View child = logScroll.getChildAt(
                                                logScroll.getChildCount() - 1 );
                                        int diff = child.getBottom()
                                                - ( logScroll.getHeight() + logScroll.getScrollY() );
                                        follow = diff <= 24;
                                }
                                logView.append( text );
                                if( follow && logScroll != null )
                                        logScroll.post( new Runnable() {
                                                @Override
                                                public void run() {
                                                        logScroll.fullScroll( View.FOCUS_DOWN );
                                                }
                                        });
                        }
                });
        }

        private void copyLog() {
                CharSequence text = logView.getText();
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService( CLIPBOARD_SERVICE );
                if( cm != null ) {
                        cm.setPrimaryClip( android.content.ClipData.newPlainText(
                                "amxxpc log", text ));
                        Toast.makeText( this, R.string.compiler_copied,
                                Toast.LENGTH_SHORT ).show();
                }
        }

        private static String join( List<String> parts, String sep ) {
                StringBuilder sb = new StringBuilder();
                for( int i = 0; i < parts.size(); i++ ) {
                        if( i > 0 )
                                sb.append( sep );
                        sb.append( parts.get( i ));
                }
                return sb.toString();
        }
}
