package su.xash.cs16client;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shared bottom tab bar: the active tab shows the pill
 * background + white icon + bold white label; tapping another tab
 * switches screens without stacking activities. Tapping the active
 * tab does nothing.
 */
final class TabBar {
	static final int TAB_LAUNCH = 0;
	static final int TAB_PLUGINS = 1;
	static final int TAB_COMPILE = 2;

	private TabBar() {
	}

	static void init( final Activity activity, int active ) {
		style( activity, R.id.tab_launch_icon, R.id.tab_launch_label,
			R.drawable.ic_play_white, R.drawable.ic_play_gray,
			active == TAB_LAUNCH );
		style( activity, R.id.tab_plugins_icon, R.id.tab_plugins_label,
			R.drawable.ic_list_white, R.drawable.ic_list_gray,
			active == TAB_PLUGINS );
		style( activity, R.id.tab_compile_icon, R.id.tab_compile_label,
			R.drawable.ic_code_white, R.drawable.ic_code_gray,
			active == TAB_COMPILE );

		bind( activity, R.id.tab_launch, TAB_LAUNCH, active, MainActivity.class );
		bind( activity, R.id.tab_plugins, TAB_PLUGINS, active, PluginsActivity.class );
		bind( activity, R.id.tab_compile, TAB_COMPILE, active, CompilerActivity.class );
	}

	private static void style( Activity a, int iconId, int labelId,
			int whiteIcon, int grayIcon, boolean on ) {
		ImageView icon = (ImageView) a.findViewById( iconId );
		TextView label = (TextView) a.findViewById( labelId );
		if( icon != null ) {
			icon.setImageResource( on ? whiteIcon : grayIcon );
			if( on )
				icon.setBackgroundResource( R.drawable.tab_active_bg );
			else
				icon.setBackground( null );
		}
		if( label != null ) {
			label.setTextColor( on ? 0xFFE8ECF4 : 0xFF7E8CA0 );
			label.setTypeface( null, on ? Typeface.BOLD : Typeface.NORMAL );
		}
	}

	private static void bind( final Activity activity, int tabId,
			final int tabIdx, final int activeIdx,
			final Class<?> target ) {
		View tab = activity.findViewById( tabId );
		if( tab == null )
			return;
		tab.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				if( tabIdx == activeIdx )
					return;	// already on this screen
				Intent i = new Intent( activity, target );
				i.addFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP
					| Intent.FLAG_ACTIVITY_SINGLE_TOP );
				activity.startActivity( i );
				if( !( activity instanceof MainActivity ))
					activity.finish();
				// no slide: the tab bar stays put, only the content
				// switches in place
				activity.overridePendingTransition( 0, 0 );
			}
		});
	}
}
