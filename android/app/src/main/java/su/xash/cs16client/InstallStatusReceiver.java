package su.xash.cs16client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/**
 * Receives the result of the framework PackageInstaller session started by
 * UpdateChecker: shows the system install confirmation, reports success or
 * failure of the update install.
 */
public class InstallStatusReceiver extends BroadcastReceiver {

    private static final String TAG = "InstallStatus";

    @Override
    public void onReceive( Context context, Intent intent ) {
        if( intent == null )
            return;

        int status = intent.getIntExtra( PackageInstaller.EXTRA_STATUS, -1 );

        switch( status ) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // the system wants the user to confirm the install
                Intent confirm = intent.getParcelableExtra( Intent.EXTRA_INTENT );
                if( confirm != null ) {
                    confirm.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
                    try {
                        context.startActivity( confirm );
                    } catch( Throwable t ) {
                        Log.w( TAG, "cannot open install confirmation", t );
                    }
                }
                break;

            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText( context, "Update installed", Toast.LENGTH_LONG ).show();
                // leave the freshly updated app for the user to reopen
                if( Build.VERSION.SDK_INT >= 21 )
                    android.os.Process.killProcess( android.os.Process.myPid() );
                break;

            default:
                Log.w( TAG, "install failed: status=" + status + " "
                        + intent.getStringExtra( PackageInstaller.EXTRA_STATUS_MESSAGE ) );
                break;
        }
    }
}
