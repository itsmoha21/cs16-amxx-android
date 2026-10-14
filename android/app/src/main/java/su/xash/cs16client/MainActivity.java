package su.xash.cs16client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
        private static final String TAG = "CS16Client";

        // Version stamp for assets/addons.zip. Tied to the app version so
        // every new build re-extracts the bundle - a manual bump was easy
        // to forget and updated .so files silently stayed on disk after an
        // app update ("the update didn't replace all of them").
        // (modules.ini is always refreshed so the sqlite/"sqlx" class
        //  that plugins like amx_gag need is enabled; amxx configs are
        //  kept - see extractZipIfNeeded)
        // amxx-1.9.0.5304: metamod rebuilt with RTLD_GLOBAL game-DLL loading
        // (ReAPI entity-class dynamic_cast fix for get_member_s plugins)
        // amxx-1.9.0.5309: plugins.ini read from the public gamedir when the
        // private module-dir copy misses (only *.so files are copied there;
        // AMX Mod X attaches again), debug spam removed from engine.log
        // amxx-1.9.0.5310: startup pass skips already-loaded child plugins
        // and regcvar memloc misses are gated debug -- clean boot log
        private static final String ADDONS_VERSION = "amxx-1.9.0.5310-" + BuildConfig.VERSION_NAME;

        // separate gate for the bot asset sync (yaPB conf/graphs, eBot
        // configs/waypoints, ReGameDLL BotProfile/BotChatter dbs) - bump
        // this alone when those change so the addons.zip bundle stays
        // untouched.
        // r3: the sync ships in the APK again (the native step used to
        //  delete the tree from the assets), the presence check covers
        //  avatars.cfg + BotProfile.db, and those two databases are
        //  installed to the gamedir root for the first time
        private static final String BOT_ASSETS_VERSION = "ebot-wp-r3";

        private boolean mPermissionAsked = false;

        @Override
        protected void onCreate( Bundle savedInstanceState ) {
                super.onCreate( savedInstanceState );

                mPermissionAsked = savedInstanceState != null
                        && savedInstanceState.getBoolean( "permission_asked", false );

                // ask at most once per install - remembering the
                // flag across launches stops the per-launch dialog nagging
                if( !mPermissionAsked ) {
                        mPermissionAsked = getPreferences( MODE_PRIVATE )
                                .getBoolean( "permission_asked", false );
                }

                // blocking update gate: when GitHub lists a newer build the
                // launcher offers the update and refuses to continue until
                // it is installed (no-op when offline or up to date)
                UpdateChecker.check( this );

                // Always render the launcher UI first so the user is never
                // left on a blank/stuck screen. If storage access is missing,
                // the menu shows a tappable "storage access required" banner
                // and (on the very first ask) we open the system settings page
                // once so they can grant it. onResume() finishes the install
                // once the permission is granted.
                if( !hasStorageAccess() ) {
                        showMenu();

                        if( !mPermissionAsked ) {
                                mPermissionAsked = true;
                                requestStorageAccess();
                        }
                        return;
                }

                proceed();
        }

        @Override
        protected void onSaveInstanceState( Bundle outState ) {
                super.onSaveInstanceState( outState );
                outState.putBoolean( "permission_asked", mPermissionAsked );
        }

        @Override
        protected void onResume() {
                super.onResume();

                // finish the install once the user grants storage access in
                // system settings; otherwise just keep the banner in sync
                // (the persistent banner is the call-to-action, no toast spam)
                if( hasStorageAccess() ) {
                        mPermissionAsked = false;
                        proceed();
                } else {
                        updateStorageBanner();
                }
        }

        private void proceed() {
                syncSwitchPrefsWithMarkers();

                try {
                        extractAddonsIfNeeded();
                } catch( Throwable t ) {
                        Log.w( TAG, "addons extraction failed", t );
                        Toast.makeText( this, "Addons installation error: " + t.getMessage(), Toast.LENGTH_LONG ).show();
                        logInstallError( "cstrike addons (cs16client)", t );
                }

                // the fvault seeds ride with no zip; they appear only while
                // the Zombie Plague X pack is installed and enabled
                ensureZpxVaultSeeds();

                showMenu();
        }

        // ------------------------------------------------------------------
        // append install problems to <xash>/amxx-install-log.txt so we can
        // see the exact reason (EACCES, missing asset, ...) when the user
        // reports a generic "it gave an error"
        // ------------------------------------------------------------------

        private void logInstallError( String source, Throwable t ) {
                try {
                        File dir = getXashDir();
                        if( !dir.isDirectory() )
                                dir.mkdirs();

                        java.io.PrintWriter pw = new java.io.PrintWriter(
                                new java.io.FileWriter( new File( dir, "amxx-install-log.txt" ), true ));
                        pw.println( "--- " + new java.util.Date() + " ---" );
                        pw.println( "source: " + source );
                        pw.println( t.getClass().getName() + ": " + t.getMessage() );
                        StackTraceElement[] st = t.getStackTrace();
                        for( int i = 0; i < st.length && i < 8; i++ )
                                pw.println( "    at " + st[i] );
                        if( t.getCause() != null )
                                pw.println( "cause: " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage() );
                        pw.println();
                        pw.close();
                } catch( Throwable ignored ) {
                        // logging must never crash anything
                }
        }

        // ------------------------------------------------------------------
        // storage access (the game directory lives on shared storage, next to
        // the engine's base dir /storage/emulated/0/xash)
        // ------------------------------------------------------------------

        private boolean hasStorageAccess() {
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R )
                        return Environment.isExternalStorageManager();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
                        return checkSelfPermission( android.Manifest.permission.WRITE_EXTERNAL_STORAGE )
                                == PackageManager.PERMISSION_GRANTED;

                return true;
        }

        private void requestStorageAccess() {
                getPreferences( MODE_PRIVATE ).edit().putBoolean( "permission_asked", true ).apply();
                updateStorageBanner();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                        // 1) dedicated per-app "All files access" page
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse( "package:" + getPackageName() )));
                                return;
                        } catch( Exception ignored ) {
                        }

                        // 2) the general "All files access" list (some OEM skins
                        //    lack the per-app page)
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION ));
                                return;
                        } catch( Exception ignored ) {
                        }

                        // 3) app details - "All files access" is reachable from
                        //    Special access on any device (Samsung One UI, ...)
                        try {
                                startActivity( new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse( "package:" + getPackageName() )));
                        } catch( Exception ex ) {
                                Log.w( TAG, "no storage settings page", ex );
                        }
                } else if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                        requestPermissions( new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1 );
                }
        }

        private void updateStorageBanner() {
                android.view.View banner = findViewById( R.id.storage_banner );
                if( banner != null )
                        banner.setVisibility( hasStorageAccess()
                                ? android.view.View.GONE : android.view.View.VISIBLE );
        }

        @Override
        public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
                super.onRequestPermissionsResult( requestCode, permissions, grantResults );

                if( requestCode == 1 ) {
                        if( grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
                                mPermissionAsked = false;
                                proceed();
                        } else {
                                Toast.makeText( this,
                                        "Cannot install addons without storage access",
                                        Toast.LENGTH_LONG ).show();
                        }
                }
        }

        // ------------------------------------------------------------------
        // AMX Mod X addons auto-install
        //
        // assets/addons.zip mirrors the game directory layout:
        //   cstrike/addons/metamod/...
        //   cstrike/addons/amxmodx/...
        // It is extracted into /storage/emulated/0/xash so that the patched
        // engine (su.xash.engine.test) finds the metamod library when the
        // server gamelib is loaded and relays plugin loading to AMX Mod X.
        // ------------------------------------------------------------------

        private File getXashDir() {
                return new File( Environment.getExternalStorageDirectory(), "xash" );
        }

        private void extractAddonsIfNeeded() throws Exception {
                extractAddons( false );
        }

        /**
         * force = true is the manual "Re-install addons" button: restore
         * every bundled file to stock, configs included. Files this app
         * never shipped (the user's own plugins, waypoints, saved plugin
         * data) are never touched on any path - force only resets files
         * that come from our own bundle.
         */
        private void extractAddons( boolean force ) throws Exception {
                // bot packs (YaPB conf/graphs, EBot configs) ship as individual
                // assets, not inside addons.zip - sync them onto disk first
                Map<String, String> manifest = loadShippedManifest();

                // pre-manifest installs: first run of a build that tracks file
                // checksums. The previous build extracted everything on disk,
                // so bootstrap the manifest and refresh the whole bundle once,
                // exactly like the old update path did - from then on user
                // edits are protected by their checksums.
                boolean bootstrap = manifest.isEmpty() && (
                        getPreferences( MODE_PRIVATE ).getString( "addons_version", null ) != null
                        || getPreferences( MODE_PRIVATE ).getString( "bot_assets_version", null ) != null );
                boolean full = force || bootstrap;
                // only the user-pressed reinstall button may reset configs
                // to stock; a bootstrap (upgrade from a pre-manifest build)
                // keeps them, exactly like the old update path did
                boolean factoryReset = force;

                // cstrike addons (AMX Mod X for Counter-Strike) -- this app
                // installs ONLY the counter-strike addon pack. The valve
                // (Half-Life) AMX addons are handled by the engine app itself
                // (Xash3D FWGS (AMXX)) at valve game launch since v9.
                extractBotAssetsIfNeeded( full, factoryReset, manifest );
                extractZipIfNeeded( "addons.zip", "addons_version",
                        "cstrike/addons/metamod/dlls/libmetamod_android_arm64.so",
                        full, factoryReset, manifest );
                saveShippedManifest( manifest );

                // the stale-file cleanup is GONE. It deleted the
                // ProBaseBuilder file set (bb plugins, Pro_basebuilder.ini and
                // scripting/include/basebuilder.inc) on EVERY launch, so anything
                // the user installed with those names vanished at the next app
                // start. The one-off leftover purge already ran on every launch
                // of the earlier builds, fresh installs never had those files,
                // and basebuilder.inc is package content again - keeping the
                // purge contradicted the package itself.

                // keep plugins.ini in sync with this app's nativeLibraryDir
                // (YaPB as metamod plugin) - must run on every launch in case the
                // engine/game ABI layout changed, and it is cheap anyway.
                patchPluginsIni();
        }

        private int extractZipIfNeeded( String assetName, String prefKey,
                String markerRelPath, boolean full, boolean factoryReset,
                Map<String, String> manifest ) throws Exception {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                String installed = prefs.getString( prefKey, null );

                File markerLib = new File( getXashDir(), markerRelPath );

                if( !full && ADDONS_VERSION.equals( installed ) && markerLib.isFile() ) {
                        Log.i( TAG, "AMXX addons up to date (" + assetName + ": " + ADDONS_VERSION + ")" );
                        return 0;
                }

                File dest = getXashDir();
                String destCanon = dest.getCanonicalPath();
                byte[] buffer = new byte[65536];
                int count = 0;

                InputStream in = getAssets().open( assetName );
                ZipInputStream zis = new ZipInputStream( in );

                ZipEntry entry;
                while(( entry = zis.getNextEntry() ) != null ) {
                        if( entry.isDirectory() )
                                continue;

                        String rel = entry.getName();
                        File outFile = new File( dest, rel );

                        // zip-slip guard
                        if( !outFile.getCanonicalPath().startsWith( destCanon + File.separator )) {
                                Log.w( TAG, "skipping suspicious zip entry: " + rel );
                                continue;
                        }

                        // user-owned or user-edited file: keep the disk copy
                        if( !mayOverwrite( outFile, rel, manifest, full,
                                factoryReset )) {
                                Log.i( TAG, "keeping user file: " + rel );
                                zis.closeEntry();
                                continue;
                        }

                        File parent = outFile.getParentFile();
                        if( parent != null && !parent.isDirectory() )
                                parent.mkdirs();

                        FileOutputStream fos = new FileOutputStream( outFile );
                        int n;
                        while(( n = zis.read( buffer )) > 0 )
                                fos.write( buffer, 0, n );
                        fos.close();

                        // native libraries may need the exec bit depending on the filesystem
                        if( outFile.getName().endsWith( ".so" ))
                                outFile.setExecutable( true, false );

                        // remember what we wrote so later updates can tell our
                        // files apart from user edits
                        manifest.put( rel, sha256Of( outFile ));

                        count++;
                        zis.closeEntry();
                }
                zis.close();

                prefs.edit().putString( prefKey, ADDONS_VERSION ).apply();
                Log.i( TAG, "AMXX addons extracted (" + assetName + "): " + count
                        + " files -> " + dest.getAbsolutePath() );
                Toast.makeText( this, "AMX Mod X addons installed (" + assetName + ": "
                        + count + " files)", Toast.LENGTH_SHORT ).show();
                return count;
        }

        // ------------------------------------------------------------------
        // file ownership for the addon update paths
        //
        // every file this app writes into the game tree is recorded with its
        // SHA-256 in a manifest next to the metamod markers (on disk, so it
        // survives app reinstalls like they do). A later update may then:
        //   - refresh a bundled file that is untouched since we wrote it
        //   - keep a bundled file the user edited (checksum mismatch)
        //   - never touch a file we never wrote (user's own addons)
        // The "Re-install addons" button passes full = factoryReset = true
        // and resets every bundled file to stock, configs included - the one
        // way back to a stock bundle. The one-time bootstrap (upgrade from a
        // pre-manifest build) passes full only: it refreshes the bundle but
        // keeps user configs, exactly like the old update path did.
        // ------------------------------------------------------------------

        /** true when the bundle may write rel over the disk copy. */
        private boolean mayOverwrite( File outFile, String rel,
                Map<String, String> manifest, boolean full,
                boolean factoryReset ) {
                // analyzed/user-provided waypoint files win over the shipped
                // pack on every path, reinstall included
                if( outFile.isFile() && rel.endsWith( ".ewp" ))
                        return false;

                // fvault vaults (ranks/points/packs plugins save) are player
                // progress and must never be overwritten on any path,
                // reinstall included. The Zombie Plague X seeds are created
                // at runtime by ensureZpxVaultSeeds() only (no zip ships
                // them), so nothing in a bundle should ever land here -
                // this guard keeps that true even if a zip regains them.
                if( outFile.isFile() && rel.contains( "/addons/amxmodx/data/file_vault/" ))
                        return false;

                // modules.ini is the module loader list and MUST match the
                // shipped .so set (it enables sqlite, which provides the
                // "sqlx" class that plugins like amx_gag require) - always
                // refreshed
                if( rel.endsWith( "modules.ini" ))
                        return true;

                // the reinstall button resets everything bundled to stock,
                // configs included: it is the one way back to a stock bundle
                if( factoryReset )
                        return true;

                // on updates (and the one-time bootstrap) amxx configs are
                // user-owned data: the plugin list, admins (users.ini),
                // cvars... they are edited after the first install and an
                // update must not clobber them
                if( outFile.isFile() && rel.contains( "/addons/amxmodx/configs/" ))
                        return false;

                if( full || !outFile.isFile() )
                        return true;

                String recorded = manifest.get( rel );
                if( recorded == null )
                        return false; // on disk but never written by us: user's

                try {
                        // unchanged since we wrote it -> bundled update wins
                        return recorded.equalsIgnoreCase( sha256Of( outFile ));
                } catch( Throwable t ) {
                        return false; // unreadable -> keep the disk copy
                }
        }

        private File shippedManifestFile() {
                return new File( getXashDir(),
                        "cstrike/addons/metamod/.cs16client-shipped" );
        }

        private Map<String, String> loadShippedManifest() {
                Map<String, String> map = new HashMap<String, String>();
                try {
                        File f = shippedManifestFile();
                        if( !f.isFile() )
                                return map;
                        BufferedReader r = new BufferedReader( new FileReader( f ));
                        String line;
                        while(( line = r.readLine() ) != null ) {
                                // "<64 hex> <relpath>"
                                if( line.length() > 65 && line.charAt( 64 ) == ' ' )
                                        map.put( line.substring( 65 ), line.substring( 0, 64 ));
                        }
                        r.close();
                } catch( Throwable t ) {
                        Log.w( TAG, "shipped manifest unreadable", t );
                }
                return map;
        }

        private void saveShippedManifest( Map<String, String> map ) {
                try {
                        File f = shippedManifestFile();
                        if( f.getParentFile() != null )
                                f.getParentFile().mkdirs();
                        PrintWriter pw = new PrintWriter( new FileWriter( f, false ));
                        for( Map.Entry<String, String> e : map.entrySet() )
                                pw.println( e.getValue() + " " + e.getKey() );
                        pw.close();
                } catch( Throwable t ) {
                        Log.w( TAG, "cannot write shipped manifest", t );
                }
        }

        private static String sha256Of( File f ) throws Exception {
                MessageDigest md = MessageDigest.getInstance( "SHA-256" );
                FileInputStream in = new FileInputStream( f );
                try {
                        byte[] buf = new byte[65536];
                        int n;
                        while(( n = in.read( buf )) > 0 )
                                md.update( buf, 0, n );
                } finally {
                        in.close();
                }
                StringBuilder sb = new StringBuilder();
                for( byte b : md.digest() )
                        sb.append( Character.forDigit(( b >> 4 ) & 0xF, 16 ))
                          .append( Character.forDigit( b & 0xF, 16 ));
                return sb.toString();
        }

        /**
         * Rewrite metamod plugins.ini after the addons extraction.
         *
         * - The AMX Mod X core plugin keeps its relative path (it lives inside
         *   the extracted addons tree).
         * - YaPB is loaded as a METAMOD PLUGIN now (-dll @yapb removed:
         *   the yapb wrapper does not re-export entity spawn symbols, so the
         *   engine failed every map entity with "No spawn function"). YaPB
         *   needs an absolute path to its library inside this app's
         *   nativeLibraryDir, which is only known at runtime - so we write it
         *   here, right after the extraction.
         */
        private void patchPluginsIni() {
                try {
                        File ini = new File( getXashDir(), "cstrike/addons/metamod/plugins.ini" );
                        File dir = ini.getParentFile();
                        if( dir == null )
                                return;
                        dir.mkdirs();

                        // AMX Mod X and YaPB are independent switches (both can
                        // be enabled at the same time). The engine app gates each
                        // plugins.ini line through a marker file next to the ini,
                        // because this app cannot read the engine's SharedPreferences.
                        // When BOTH are disabled the engine bypasses metamod entirely
                        // (XASH3D_DISABLE_AMXX) and the ini contents do not matter.
                        boolean amxxEnabled = !new File( dir, "amxmodx.disabled" ).isFile();
                        boolean yapbEnabled = !new File( dir, "yapb.disabled" ).isFile();
                        boolean ebotEnabled = !new File( dir, "ebot.disabled" ).isFile();

                        StringBuilder sb = new StringBuilder();
                        sb.append( ";;;\n" );
                        sb.append( "; Metamod plugin list for Android (xash3d-fwgs)\n" );
                        sb.append( "; AMX Mod X core:\n" );
                        sb.append( ";;;\n" );

                        // preserve plugin lines the USER added to plugins.ini.
                        // Everything that is not one of the two lines this app
                        // manages survives the rewrite (metamod ignores what it
                        // cannot load, so a stale custom line is harmless).
                        java.util.List<String> custom = new java.util.ArrayList<String>();
                        try {
                                java.io.BufferedReader br = new java.io.BufferedReader(
                                        new java.io.FileReader( ini ) );
                                String line;
                                while(( line = br.readLine()) != null ) {
                                        String t = line.trim();
                                        if( !t.startsWith( "linux " ) )
                                                continue;
                                        if( t.contains( "libmm_amxmodx.so" ) ||
                                                t.contains( "libyapb_android_" ) ||
                                                t.contains( "libebot_android_" ) )
                                                continue;
                                        if( !custom.contains( t ) )
                                                custom.add( t );
                                }
                                br.close();
                        } catch( Throwable ignored ) {
                                // no previous ini / unreadable - nothing to preserve
                        }

                        if( amxxEnabled ) {
                                sb.append( "linux addons/amxmodx/dlls/libmm_amxmodx.so\n" );
                        } else {
                                sb.append( "; disabled by the AMX Mod X switch: addons/metamod/amxmodx.disabled\n" );
                        }

                        // YaPB bot plugin (absolute path, ABI-dependent). EBot
                        // and YaPB both fill the bot slots, so only one can be
                        // loaded: the switches keep them exclusive and ebot
                        // wins in here if both markers are somehow absent.
                        if( yapbEnabled && !ebotEnabled ) {
                                String nativeDir = getApplicationInfo().nativeLibraryDir;
                                String[] abis = android.os.Build.SUPPORTED_ABIS;
                                String yapbName = null;
                                if( abis != null && abis.length > 0 && abis[0].contains( "arm64" ) )
                                        yapbName = "libyapb_android_arm64.so";
                                else if( abis != null && abis.length > 0 )
                                        yapbName = "libyapb_android_armv7l.so";

                                if( yapbName != null && nativeDir != null ) {
                                        File yapb = new File( nativeDir, yapbName );
                                        if( yapb.isFile() ) {
                                                sb.append( ";\n; YaPB bot (as metamod plugin):\n" );
                                                sb.append( "linux " ).append( yapb.getAbsolutePath() ).append( "\n" );
                                        }
                                }
                        } else if( ebotEnabled ) {
                                sb.append( "; YaPB bots paused: EBot is enabled (only one bot fills the slots)\n" );
                        } else {
                                sb.append( "; YaPB bots disabled by the YaPB switch: addons/metamod/yapb.disabled\n" );
                        }

                        // EBot bot plugin (absolute path, ABI-dependent)
                        if( ebotEnabled ) {
                                String nativeDir = getApplicationInfo().nativeLibraryDir;
                                String[] abis = android.os.Build.SUPPORTED_ABIS;
                                String ebotName = null;
                                if( abis != null && abis.length > 0 && abis[0].contains( "arm64" ) )
                                        ebotName = "libebot_android_arm64.so";
                                else if( abis != null && abis.length > 0 )
                                        ebotName = "libebot_android_armv7l.so";

                                if( ebotName != null && nativeDir != null ) {
                                        File ebot = new File( nativeDir, ebotName );
                                        if( ebot.isFile() ) {
                                                sb.append( ";\n; EBot bot (as metamod plugin):\n" );
                                                sb.append( "linux " ).append( ebot.getAbsolutePath() ).append( "\n" );
                                        }
                                }
                        } else {
                                sb.append( "; EBot bots disabled by the EBot switch: addons/metamod/ebot.disabled\n" );
                        }

                        if( !custom.isEmpty() ) {
                                sb.append( ";\n; custom plugin lines (kept from the previous plugins.ini):\n" );
                                for( String t : custom )
                                        sb.append( t ).append( "\n" );
                        }

                        java.io.PrintWriter pw = new java.io.PrintWriter( new java.io.FileWriter( ini, false ) );
                        pw.print( sb.toString() );
                        pw.close();
                        Log.i( TAG, "plugins.ini patched: " + ini.getAbsolutePath()
                                + " (amxx=" + amxxEnabled + ", yapb=" + yapbEnabled
                                + ", ebot=" + ebotEnabled + ")" );
                } catch( Throwable t ) {
                        Log.w( TAG, "plugins.ini patch failed", t );
                }
        }

        // ------------------------------------------------------------------
        // engine launch
        // ------------------------------------------------------------------

        private void launchEngine() {
                String pkg = "su.xash.engine.test";

                try {
                        getPackageManager().getPackageInfo( pkg, 0 );
                } catch( PackageManager.NameNotFoundException e ) {
                        try {
                                pkg = "su.xash.engine";
                                getPackageManager().getPackageInfo( pkg, 0 );
                        } catch( PackageManager.NameNotFoundException ex ) {
                                startActivity( new Intent( Intent.ACTION_VIEW, Uri.parse( "https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous" ) ).setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK ) );
                                finish();
                                return;
                        }
                }

                Intent launch = new Intent().setComponent( new ComponentName( pkg, "su.xash.engine.XashActivity" ) )
                        .setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK )
                        .putExtra( "gamedir", "cstrike" )
                        .putExtra( "gamelibdir", getApplicationInfo().nativeLibraryDir )
                        .putExtra( "argv", "-dev 2 -log" )  // no -dll @yapb -- YaPB loads via metamod plugins.ini
                        .putExtra( "package", getPackageName() );

                // both switches off: bypass metamod entirely and load the
                // original server library directly (same gate the engine
                // menu used to send as XASH3D_DISABLE_AMXX).
                if( !isAmxxOn() && !isYapbOn() && !isEbotOn() )
                        launch.putExtra( "env", new String[]{ "XASH3D_DISABLE_AMXX", "1" } );

                startActivity( launch );
                finish();
        }

        // ------------------------------------------------------------------
        // launcher menu (cstrike only): settings + launch, like the engine
        // app's main menu. The switches persist in this app's preferences
        // and take effect through the metamod marker files + plugins.ini,
        // which patchPluginsIni() already honours.
        // ------------------------------------------------------------------

        private void showMenu() {
                setContentView( R.layout.activity_launcher );

                final PillSwitch amxxSwitch = (PillSwitch) findViewById( R.id.menu_enable_amxx );
                final PillSwitch yapbSwitch = (PillSwitch) findViewById( R.id.menu_enable_yapb );
                final PillSwitch ebotSwitch = (PillSwitch) findViewById( R.id.menu_enable_ebot );

                amxxSwitch.setChecked( isAmxxOn() );
                yapbSwitch.setChecked( isYapbOn() );
                ebotSwitch.setChecked( isEbotOn() );

                PillSwitch.OnCheckedChangeListener toggleListener =
                                new PillSwitch.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged( PillSwitch buttonView, boolean isChecked ) {
                                // EBot and YaPB both fill the bot slots, so only
                                // one bot can be active: switching one on turns
                                // the other off (setChecked re-enters this
                                // listener, the nested write is idempotent).
                                if( buttonView == ebotSwitch && isChecked && yapbSwitch.isChecked() )
                                        yapbSwitch.setChecked( false );
                                if( buttonView == yapbSwitch && isChecked && ebotSwitch.isChecked() )
                                        ebotSwitch.setChecked( false );

                                getPreferences( MODE_PRIVATE ).edit()
                                        .putBoolean( "enable_amxx", amxxSwitch.isChecked() )
                                        .putBoolean( "enable_yapb_bots", yapbSwitch.isChecked() )
                                        .putBoolean( "enable_ebot_bots", ebotSwitch.isChecked() )
                                        .apply();
                                writeMetaMarker( "amxmodx.disabled", !amxxSwitch.isChecked() );
                                writeMetaMarker( "yapb.disabled", !yapbSwitch.isChecked() );
                                writeMetaMarker( "ebot.disabled", !ebotSwitch.isChecked() );
                                writeMetaMarker( "ebot.enabled", ebotSwitch.isChecked() );
                                patchPluginsIni();
                                refreshMenuStatus();
                        }
                };
                amxxSwitch.setOnCheckedChangeListener( toggleListener );
                yapbSwitch.setOnCheckedChangeListener( toggleListener );
                ebotSwitch.setOnCheckedChangeListener( toggleListener );

                ((android.view.View) findViewById( R.id.row_amxx )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                amxxSwitch.toggle();
                        }
                });
                ((android.view.View) findViewById( R.id.row_yapb )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                yapbSwitch.toggle();
                        }
                });
                ((android.view.View) findViewById( R.id.row_ebot )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                ebotSwitch.toggle();
                        }
                });

                ((Button) findViewById( R.id.menu_launch )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                onLaunchPressed();
                        }
                });

                // shared bottom tab bar: Launch tab is inert here (the
                // active tab does nothing on tap), the other tabs
                // open their screens.
                TabBar.init( this, TabBar.TAB_LAUNCH );

                ((android.view.View) findViewById( R.id.menu_reinstall )).setOnClickListener(
                                new android.view.View.OnClickListener() {
                        @Override
                        public void onClick( android.view.View v ) {
                                if( !hasStorageAccess() ) {
                                        requestStorageAccess();
                                        return;
                                }

                                // true reinstall: drop both stamps and let the
                                // extraction reset every BUNDLED file to stock.
                                // The user's own addons are not part of the
                                // bundle and are never touched (waypoints stay
                                // protected too - see mayOverwrite).
                                getPreferences( MODE_PRIVATE ).edit()
                                        .remove( "addons_version" )
                                        .remove( "bot_assets_version" ).apply();
                                try {
                                        extractAddons( true );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "addons re-install failed", t );
                                        Toast.makeText( MainActivity.this,
                                                "Addons installation error: " + t.getMessage(),
                                                Toast.LENGTH_LONG ).show();
                                        logInstallError( "cstrike addons reinstall (cs16client)", t );
                                }
                                patchPluginsIni();
                                // reinstall never deletes vault files, but a
                                // fresh tree (or a pack installed afterwards)
                                // still needs its seeds back
                                ensureZpxVaultSeeds();
                                refreshMenuStatus();
                                Toast.makeText( MainActivity.this,
                                        R.string.menu_reinstalled, Toast.LENGTH_SHORT ).show();
                        }
                });

                try {
                        String ver = getPackageManager()
                                .getPackageInfo( getPackageName(), 0 ).versionName;
                        ((TextView) findViewById( R.id.menu_footer )).setText(
                                "CS16Client  v" + ver );
                } catch( Throwable ignored ) {
                }

                // storage banner: tapping re-opens the permission settings page
                android.view.View storageBanner = findViewById( R.id.storage_banner );
                if( storageBanner != null ) {
                        storageBanner.setOnClickListener(
                                        new android.view.View.OnClickListener() {
                                @Override
                                public void onClick( android.view.View v ) {
                                        requestStorageAccess();
                                }
                        });
                }
                updateStorageBanner();

                refreshMenuStatus();
        }

        private void onLaunchPressed() {
                if( !hasStorageAccess() ) {
                        // can't install or run without storage - route to settings
                        requestStorageAccess();
                        return;
                }

                try {
                        extractAddonsIfNeeded();
                } catch( Throwable t ) {
                        Log.w( TAG, "addons extraction failed", t );
                        logInstallError( "cstrike addons (cs16client)", t );
                }
                patchPluginsIni();
                // a pack installed after app start still gets its seeds
                // before the engine runs
                ensureZpxVaultSeeds();
                launchEngine();
        }

        private void refreshMenuStatus() {
                TextView engineStatus = (TextView) findViewById( R.id.menu_status_engine );
                TextView addonsStatus = (TextView) findViewById( R.id.menu_status_addons );
                android.widget.ImageView engineIcon = (android.widget.ImageView)
                        findViewById( R.id.status_engine_icon );
                android.widget.ImageView addonsIcon = (android.widget.ImageView)
                        findViewById( R.id.status_addons_icon );
                if( engineStatus == null || addonsStatus == null )
                        return;

                boolean engineOk = enginePresent();
                engineStatus.setText( engineOk
                        ? getString( R.string.status_installed )
                        : getString( R.string.status_missing ));
                engineStatus.setTextColor( engineOk ? 0xFF8B93A3 : 0xFFFF6B61 );
                if( engineIcon != null )
                        engineIcon.setImageResource( engineOk
                                ? R.drawable.ic_check : R.drawable.ic_warn );

                File marker = new File( getXashDir(),
                        "cstrike/addons/metamod/dlls/libmetamod_android_arm64.so" );
                boolean addonsOk = marker.isFile();
                addonsStatus.setText( addonsOk
                        ? getString( R.string.status_installed )
                        : getString( R.string.status_will_install ));
                addonsStatus.setTextColor( addonsOk ? 0xFF8B93A3 : 0xFFFFC25E );
                if( addonsIcon != null )
                        addonsIcon.setImageResource( addonsOk
                                ? R.drawable.ic_check : R.drawable.ic_warn );
        }

        private boolean enginePresent() {
                try {
                        getPackageManager().getPackageInfo( "su.xash.engine.test", 0 );
                        return true;
                } catch( PackageManager.NameNotFoundException e ) {
                        try {
                                getPackageManager().getPackageInfo( "su.xash.engine", 0 );
                                return true;
                        } catch( PackageManager.NameNotFoundException ex ) {
                                return false;
                        }
                }
        }

        /**
         * Bot packs (YaPB conf/graphs, EBot configs) ship as individual files
         * under assets/addons/**, but both bots read them with plain libc
         * fopen() against the game directory - which cannot see inside the
         * APK. Copy them to /xash/cstrike/addons once per ADDONS_VERSION so
         * every bot file is on disk next to the metamod plugins.
         */
        private int extractBotAssetsIfNeeded( boolean full, boolean factoryReset,
                Map<String, String> manifest ) {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                File base = new File( getXashDir(), "cstrike/addons" );

                // the version stamp alone is not enough: if anything removes
                // the bot files from the game folder (a cleanup app, a manual
                // delete, a broken install) the old check would consider the
                // assets "done" forever and never restore them - verify the
                // key files are really on disk before skipping
                boolean stamped = BOT_ASSETS_VERSION.equals(
                        prefs.getString( "bot_assets_version", "" ) );
                boolean present = new File( base, "ebot/ebot.cfg" ).isFile()
                        && new File( base, "ebot/names.cfg" ).isFile()
                        && new File( base, "ebot/avatars.cfg" ).isFile()
                        && new File( base, "ebot/waypoints" ).isDirectory()
                        && new File( base, "yapb/conf/yapb.cfg" ).isFile()
                        && new File( getXashDir(), "cstrike/BotProfile.db" ).isFile();
                if( !full && stamped && present )
                        return 0;
                if( stamped )
                        Log.w( TAG, "bot assets incomplete on disk - re-extracting" );
                int count = 0;
                count += copyAssetTree( "addons/yapb", new File( base, "yapb" ),
                        false, manifest, full, factoryReset );
                count += copyAssetTree( "addons/ebot", new File( base, "ebot" ),
                        false, manifest, full, factoryReset );
                // bundled waypoints never overwrite existing .ewp files -
                // analyzed/user-provided waypoint files win over shipped ones
                // (on the reinstall path too - this rule is absolute)
                count += copyAssetTree( "addons/ebot/waypoints",
                        new File( base, "ebot/waypoints" ), true, manifest, full,
                        factoryReset );
                // keep the (bot-created) waypoints directory around
                new File( base, "ebot/waypoints" ).mkdirs();
                // ReGameDLL's built-in bot code reads these two databases
                // from the gamedir root (bot_profile_db cvar / BotChatter
                // phrase init) - they ship as APK assets but nothing used
                // to copy them to disk
                count += copyAssetTree( "BotProfile.db",
                        new File( getXashDir(), "cstrike/BotProfile.db" ),
                        false, manifest, full, factoryReset );
                count += copyAssetTree( "BotChatter.db",
                        new File( getXashDir(), "cstrike/BotChatter.db" ),
                        false, manifest, full, factoryReset );

                prefs.edit().putString( "bot_assets_version", BOT_ASSETS_VERSION ).apply();
                Log.i( TAG, "bot assets extracted: " + count + " files -> "
                        + base.getAbsolutePath() );
                return count;
        }

        private int copyAssetTree( String assetPath, File dest, boolean skipExisting,
                Map<String, String> manifest, boolean full, boolean factoryReset ) {
                int count = 0;
                try {
                        String[] children = getAssets().list( assetPath );
                        if( children == null || children.length == 0 ) {
                                // leaf = file (an empty directory marker lists
                                // empty too - then open() fails and we mkdir)
                                if( dest.exists() ) {
                                        // bundled waypoints never overwrite
                                        // existing .ewp files - analyzed /
                                        // user-provided waypoint files win
                                        if( skipExisting )
                                                return 0;
                                        // user-owned or user-edited file:
                                        // keep the disk copy
                                        if( !mayOverwrite( dest, relToXash( dest ),
                                                        manifest, full, factoryReset ))
                                                return 0;
                                }
                                InputStream in;
                                try {
                                        in = getAssets().open( assetPath );
                                } catch( Throwable notAFile ) {
                                        dest.mkdirs();
                                        return 0;
                                }
                                dest.getParentFile().mkdirs();
                                FileOutputStream out = new FileOutputStream( dest );
                                byte[] buf = new byte[16384];
                                int n;
                                while(( n = in.read( buf )) > 0 )
                                        out.write( buf, 0, n );
                                out.close();
                                in.close();
                                // record what we wrote (see mayOverwrite)
                                manifest.put( relToXash( dest ), sha256Of( dest ));
                                return 1;
                        }

                        dest.mkdirs();
                        for( String child : children ) {
                                // the ebot tree's waypoints/ subdir is synced
                                // separately with the never-overwrite policy
                                if( assetPath.equals( "addons/ebot" ) && child.equals( "waypoints" ) )
                                        continue;
                                count += copyAssetTree( assetPath + "/" + child,
                                        new File( dest, child ), skipExisting,
                                        manifest, full, factoryReset );
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "asset copy failed: " + assetPath, t );
                }
                return count;
        }

        /** path of f relative to the xash dir, '/'-separated (manifest key). */
        private String relToXash( File f ) {
                try {
                        String base = getXashDir().getCanonicalPath() + File.separator;
                        String full = f.getCanonicalPath();
                        if( full.startsWith( base ))
                                return full.substring( base.length() )
                                        .replace( File.separatorChar, '/' );
                } catch( Throwable ignored ) {
                }
                return f.getPath().replace( File.separatorChar, '/' );
        }

        private boolean isAmxxOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_amxx", true );
        }

        // ------------------------------------------------------------------
        // fvault seed files for the Zombie Plague X pack
        //
        // fvault.inc opens its vaults READ-FIRST and calls feof() on the
        // fopen() result without checking it: when the vault file is
        // missing, every rank/points/packs save dies with "Invalid file
        // handle 0 used in native feof" and the plugin gets paused. fvault
        // only mkdirs from its listing stocks, so a fresh install can never
        // heal itself.
        //
        // These vaults belong to the Zombie Plague X pack alone, so the
        // empty seeds are created here - and only while that pack is
        // actually installed and enabled. Installs that never run it (other
        // zombie mods, plain CS) never get stray vault files, and valve
        // (Half-Life) never gets them at all. Existing vault files are
        // player progress: created only when missing, never overwritten or
        // removed (see mayOverwrite).
        // ------------------------------------------------------------------

        private void ensureZpxVaultSeeds() {
                try {
                        // the pack's core plugin - Zombie Plague Advance
                        // ships a differently named one
                        // (zombie_plague_advance_*), so it can never match
                        File core = new File( getXashDir(),
                                "cstrike/addons/amxmodx/plugins/zombie_plague.amxx" );
                        if( !core.isFile() )
                                return;
                        if( !isAmxxOn() || !zpxPackListed() )
                                return;

                        File vault = new File( getXashDir(),
                                "cstrike/addons/amxmodx/data/file_vault" );
                        if( !vault.isDirectory() && !vault.mkdirs() )
                                return;

                        // vault names recovered from the pack's plugin
                        // binaries (each plugin passes its own name to
                        // fvault); Points/points_db/status_db are the names
                        // an older revision of that pack wrote
                        String[] names = {
                                "zpx_rank_system", "zpx_quests_system",
                                "zpx_save_points", "zpx_save_packs",
                                "zpx_save_zclass", "zpx_save_knife",
                                "zpx_save_get", "Points", "points_db",
                                "status_db" };
                        for( String n : names ) {
                                File f = new File( vault, n + ".txt" );
                                if( !f.isFile() )
                                        f.createNewFile();
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "zpx vault seeds failed", t );
                }
        }

        /**
         * true when zombie_plague.amxx is enabled in one of the AMXX
         * plugin lists (configs/plugins.ini + every configs/plugins-*.ini,
         * which the module loads on top of it - see
         * LoadExtraPluginsFromDir in meta_api.cpp). A commented-out line
         * ("; zombie_plague.amxx") does not count.
         */
        private boolean zpxPackListed() {
                File configs = new File( getXashDir(),
                        "cstrike/addons/amxmodx/configs" );
                File[] inis = configs.listFiles();
                if( inis == null )
                        return false;
                for( File f : inis ) {
                        String name = f.getName().toLowerCase( Locale.US );
                        if( !f.isFile() || !( name.equals( "plugins.ini" )
                                || ( name.startsWith( "plugins-" )
                                        && name.endsWith( ".ini" ))))
                                continue;
                        try {
                                BufferedReader r = new BufferedReader(
                                        new FileReader( f ));
                                String line;
                                while(( line = r.readLine()) != null ) {
                                        int c = line.indexOf( ';' );
                                        if( c >= 0 )
                                                line = line.substring( 0, c );
                                        if( line.trim().equalsIgnoreCase(
                                                "zombie_plague.amxx" )) {
                                                r.close();
                                                return true;
                                        }
                                }
                                r.close();
                        } catch( Throwable ignored ) {
                        }
                }
                return false;
        }

        /**
         * A reinstall wipes this app's SharedPreferences but the marker files
         * in /xash survive - so the switches must be re-seeded from disk when
         * the prefs are fresh, otherwise the menu would show ON while the
         * engine actually loads with AMXX/YaPB disabled.
         */
        private void syncSwitchPrefsWithMarkers() {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                if( prefs.contains( "enable_amxx" ) && prefs.contains( "enable_yapb_bots" )
                        && prefs.contains( "enable_ebot_bots" ) )
                        return;

                File metaDir = new File( getXashDir(), "cstrike/addons/metamod" );
                boolean amxx = !new File( metaDir, "amxmodx.disabled" ).isFile();
                boolean yapb = !new File( metaDir, "yapb.disabled" ).isFile();

                // EBot is opt-in: with neither marker file present (first run
                // after the update) it starts DISABLED so it can never silently
                // replace YaPB; from then on the two marker files are the
                // on-disk truth and they survive app reinstalls.
                File ebotMarker = new File( metaDir, "ebot.disabled" );
                if( !ebotMarker.isFile() && !new File( metaDir, "ebot.enabled" ).isFile() )
                        writeMetaMarker( "ebot.disabled", true );
                boolean ebot = !ebotMarker.isFile();

                prefs.edit()
                        .putBoolean( "enable_amxx", amxx )
                        .putBoolean( "enable_yapb_bots", yapb )
                        .putBoolean( "enable_ebot_bots", ebot )
                        .apply();
                Log.i( TAG, "switch prefs seeded from markers: amxx=" + amxx
                        + ", yapb=" + yapb + ", ebot=" + ebot );
        }

        private boolean isYapbOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_yapb_bots", true );
        }

        private boolean isEbotOn() {
                return getPreferences( MODE_PRIVATE ).getBoolean( "enable_ebot_bots", false );
        }

        private void writeMetaMarker( String name, boolean present ) {
                try {
                        File metaDir = new File( getXashDir(), "cstrike/addons/metamod" );
                        metaDir.mkdirs();
                        File marker = new File( metaDir, name );
                        if( present )
                                marker.createNewFile();
                        else
                                marker.delete();
                } catch( Throwable t ) {
                        Log.w( TAG, "marker write failed: " + name, t );
                }
        }
}
