package su.xash.cs16client;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forced update gate for the launcher.
 *
 * On every start the launcher asks GitHub for the latest published release
 * and compares its build number (the NN in the 1.35.NN-<hash> tag, which is
 * identical to this app's versionName) with the one compiled in. If a newer
 * build exists the user gets a blocking dark dialog: update or exit - there
 * is no "continue with the old version". Builds without a build number in
 * their versionName (pre-1.35.NN installs) count as build 0 so they are
 * asked to update as well.
 *
 * If the check cannot run (no network, GitHub unreachable, API rate limit)
 * the launcher starts normally - only a CONFIRMED newer release blocks.
 */
final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // release source; the newest published tag (prereleases included - our
    // development builds are flagged as such and /releases/latest would
    // never see them) defines the newest build
    private static final String API_URL =
            "https://api.github.com/repos/venomxtreme-cs/cs16fresh/releases?per_page=1";
    private static final String RELEASES_PAGE =
            "https://github.com/venomxtreme-cs/cs16fresh/releases";
    private static final String APK_NAME = "cs16client-app-git-release.apk";

    private static final AtomicLong sDownloadId = new AtomicLong( -1 );
    private static boolean sReceiverRegistered = false;

    private UpdateChecker() {}

    static void check(final Activity activity) {
        if( BuildConfig.IS_GOOGLE_PLAY_BUILD )
            return; // the store build updates through the store

        final int localBuild = buildNumberFrom( BuildConfig.VERSION_NAME );

        new Thread( new Runnable() {
            @Override
            public void run() {
                try {
                    String tag = fetchLatestTag();
                    if( tag == null )
                        return;

                    int remoteBuild = buildNumberFrom( tag );
                    if( remoteBuild <= localBuild )
                        return; // up to date

                    final String latestTag = tag;
                    new Handler( Looper.getMainLooper() ).post( new Runnable() {
                        @Override
                        public void run() {
                            if( activity.isFinishing() || activity.isDestroyed() )
                                return;
                            showDialog( activity, latestTag, remoteBuild );
                        }
                    } );
                } catch( Throwable t ) {
                    // offline / api unreachable -> never block the launcher
                    Log.i( TAG, "update check skipped: " + t );
                }
            }
        }, "update-check" ).start();
    }

    // ------------------------------------------------------------------

    private static String fetchLatestTag() throws Exception {
        URL url = new URL( API_URL );
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout( 6000 );
            conn.setReadTimeout( 6000 );
            conn.setRequestProperty( "User-Agent", "cs16client-launcher" );
            conn.setRequestProperty( "Accept", "application/vnd.github+json" );

            if( conn.getResponseCode() != 200 )
                return null;

            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader( new InputStreamReader( in ) );
            String line;
            while(( line = reader.readLine() ) != null )
                sb.append( line );
            reader.close();

            // the endpoint returns an array; the first entry is the newest
            // published release (drafts are hidden without auth)
            JSONArray arr = new JSONArray( sb.toString() );
            if( arr.length() == 0 )
                return null;

            JSONObject newest = arr.optJSONObject( 0 );
            String tag = newest == null ? "" : newest.optString( "tag_name", "" );
            return tag.isEmpty() ? null : tag;
        } finally {
            conn.disconnect();
        }
    }

    /** NN of a "1.35.NN-<hash>" tag/version, or 0 when there is none. */
    private static int buildNumberFrom( String name ) {
        if( name == null )
            return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile( "1\\.35\\.(\\d+)" ).matcher( name );
        if( m.find() ) {
            try {
                return Integer.parseInt( m.group( 1 ) );
            } catch( NumberFormatException ignored ) { }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // blocking dark dialog: update or exit, nothing else
    // ------------------------------------------------------------------

    private static void showDialog( final Activity activity,
            final String tag, final int remoteBuild ) {

        final Dialog dialog = new Dialog( activity );
        dialog.setCancelable( false );
        dialog.setCanceledOnTouchOutside( false );
        dialog.setOnKeyListener( new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey( android.content.DialogInterface d, int keyCode,
                    KeyEvent event ) {
                return keyCode == KeyEvent.KEYCODE_BACK; // swallow back
            }
        } );

        int pad = dp( activity, 24 );

        LinearLayout panel = new LinearLayout( activity );
        panel.setOrientation( LinearLayout.VERTICAL );
        panel.setBackgroundColor( Color.rgb( 0x14, 0x14, 0x14 ) );
        panel.setPadding( pad, pad, pad, pad );

        TextView title = new TextView( activity );
        title.setText( "Update required" );
        title.setTextColor( Color.WHITE );
        title.setTextSize( 20f );
        title.setTypeface( Typeface.DEFAULT, Typeface.BOLD );
        panel.addView( title, matchWrap() );

        TextView body = new TextView( activity );
        body.setText( "A new version is available:\n\n"
                + "development build " + remoteBuild + "  (" + tag + ")\n\n"
                + "Update to the latest version to continue." );
        body.setTextColor( Color.rgb( 0xB0, 0xB0, 0xB0 ) );
        body.setTextSize( 14f );
        body.setGravity( Gravity.START );
        LinearLayout.LayoutParams bodyLp = matchWrap();
        bodyLp.topMargin = dp( activity, 16 );
        panel.addView( body, bodyLp );

        final TextView status = new TextView( activity );
        status.setTextColor( Color.rgb( 0x8A, 0x8A, 0x8A ) );
        status.setTextSize( 13f );
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp( activity, 16 );
        panel.addView( status, statusLp );

        final ProgressBar bar = new ProgressBar( activity );
        bar.setVisibility( android.view.View.GONE );
        LinearLayout.LayoutParams barLp = matchWrap();
        barLp.topMargin = dp( activity, 8 );
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView( bar, barLp );

        LinearLayout buttons = new LinearLayout( activity );
        buttons.setOrientation( LinearLayout.HORIZONTAL );
        LinearLayout.LayoutParams buttonsLp = matchWrap();
        buttonsLp.topMargin = dp( activity, 24 );
        panel.addView( buttons, buttonsLp );

        TextView updateBtn = flatButton( activity, "UPDATE NOW",
                Color.rgb( 0x2B, 0x2B, 0x2B ), Color.WHITE );
        TextView exitBtn = flatButton( activity, "EXIT",
                Color.rgb( 0x1E, 0x1E, 0x1E ), Color.rgb( 0x9E, 0x9E, 0x9E ) );

        LinearLayout.LayoutParams updateLp = matchWrap();
        updateLp.weight = 1;
        LinearLayout.LayoutParams exitLp = matchWrap();
        exitLp.weight = 1;
        exitLp.leftMargin = dp( activity, 12 );
        buttons.addView( updateBtn, updateLp );
        buttons.addView( exitBtn, exitLp );

        final android.view.View.OnClickListener updateClick =
                new android.view.View.OnClickListener() {
            @Override
            public void onClick( android.view.View v ) {
                // one-time per device: let the user allow this launcher to
                // start the installer BEFORE anything is downloaded, so the
                // flow never dead-ends at the install step
                if( Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager()
                        .canRequestPackageInstalls() ) {
                    status.setText( "Allow installing apps for this launcher, "
                            + "then tap Update again." );
                    try {
                        activity.startActivity( new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse( "package:" + activity.getPackageName() ) ) );
                    } catch( Throwable t ) {
                        Log.w( TAG, "cannot open install permission screen", t );
                    }
                    return;
                }

                v.setEnabled( false );
                status.setText( "Downloading update..." );
                bar.setVisibility( android.view.View.VISIBLE );
                startDownload( activity, tag );
            }
        };
        updateBtn.setOnClickListener( updateClick );

        exitBtn.setOnClickListener( new android.view.View.OnClickListener() {
            @Override
            public void onClick( android.view.View v ) {
                activity.finishAffinity();
                if( Build.VERSION.SDK_INT >= 21 )
                    android.os.Process.killProcess( android.os.Process.myPid() );
            }
        } );

        FrameLayout root = new FrameLayout( activity );
        root.setBackgroundColor( Color.rgb( 0x00, 0x00, 0x00 ) );
        root.setAlpha( 0.92f );

        FrameLayout content = new FrameLayout( activity );
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER );
        content.addView( panel, contentLp );
        root.addView( content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT ) );

        dialog.setContentView( root );
        dialog.show();
    }

    // ------------------------------------------------------------------
    // download + hand the apk to the system installer
    // ------------------------------------------------------------------

    private static void startDownload( final Activity activity, String tag ) {
        String apkUrl = "https://github.com/venomxtreme-cs/cs16fresh/releases/download/"
                + tag + "/" + APK_NAME;

        DownloadManager dm = (DownloadManager)
                activity.getSystemService( Context.DOWNLOAD_SERVICE );
        if( dm == null )
            return;

        DownloadManager.Request req = new DownloadManager.Request( Uri.parse( apkUrl ) );
        req.setTitle( "Updating cs16client" );
        req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED );
        req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "cs16client-update.apk" );
        req.setMimeType( "application/vnd.android.package-archive" );

        long id = dm.enqueue( req );
        sDownloadId.set( id );
        registerInstallReceiver( activity );
    }

    private static void registerInstallReceiver( final Activity activity ) {
        if( sReceiverRegistered )
            return;
        sReceiverRegistered = true;

        activity.registerReceiver( new BroadcastReceiver() {
            @Override
            public void onReceive( Context context, Intent intent ) {
                long id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1 );
                if( id != sDownloadId.get() )
                    return;

                // DownloadManager put it into the public downloads dir
                File apk = new File( Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS ), "cs16client-update.apk" );
                if( !apk.isFile() ) {
                    Log.w( TAG, "download finished but apk missing" );
                    return;
                }

                installApk( activity, apk );
            }
        }, new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE ) );
    }

    // ------------------------------------------------------------------
    // hand the downloaded apk to the system installer (framework-only,
    // no support-library FileProvider required)
    // ------------------------------------------------------------------

    private static void installApk( Activity activity, File apk ) {
        PackageManager pm = activity.getPackageManager();

        // one-time per device: allow this launcher to start the installer
        if( Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls() ) {
            try {
                activity.startActivity( new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse( "package:" + activity.getPackageName() ) ) );
            } catch( Throwable t ) {
                Log.w( TAG, "cannot open install permission screen", t );
            }
            return;
        }

        PackageInstaller installer = pm.getPackageInstaller();
        int sessionId;
        try {
            sessionId = installer.createSession( new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL ) );
        } catch( IOException e ) {
            Log.w( TAG, "cannot create install session", e );
            return;
        }

        boolean committed = false;
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession( sessionId );

            OutputStream out = session.openWrite( "base.apk", 0, -1 );
            try {
                InputStream in = new FileInputStream( apk );
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while(( n = in.read( buf )) > 0 )
                        out.write( buf, 0, n );
                } finally {
                    in.close();
                }
                session.fsync( out );
            } finally {
                out.close();
            }

            Intent status = new Intent( activity, InstallStatusReceiver.class );
            PendingIntent pi = PendingIntent.getBroadcast( activity, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE );
            session.commit( pi.getIntentSender() );
            committed = true;
        } catch( IOException e ) {
            Log.w( TAG, "writing update apk failed", e );
        } finally {
            if( session != null ) {
                try {
                    session.close();
                } catch( Throwable ignored ) { }
            }
            if( !committed ) {
                try {
                    installer.abandonSession( sessionId );
                } catch( Throwable ignored ) { }
            }
        }
    }

    // ------------------------------------------------------------------

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT );
    }

    private static TextView flatButton( Activity activity, String text,
            int background, int textColor ) {
        TextView btn = new TextView( activity );
        btn.setText( text );
        btn.setTextColor( textColor );
        btn.setTextSize( 14f );
        btn.setTypeface( Typeface.DEFAULT, Typeface.BOLD );
        btn.setBackgroundColor( background );
        btn.setGravity( Gravity.CENTER );
        int pad = dp( activity, 14 );
        btn.setPadding( pad, pad, pad, pad );
        return btn;
    }

    private static int dp( Activity activity, int value ) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) ( value * density + 0.5f );
    }
}
