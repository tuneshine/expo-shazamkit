package expo.community.modules.shazamkit

import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import expo.community.modules.shazamkit.R
import com.facebook.react.bridge.ReactApplicationContext

class ShazamDeveloperTokenProvider(private val reactContext: ReactApplicationContext) : DeveloperTokenProvider {
    override fun provideDeveloperToken(): DeveloperToken {
        val token = reactContext.getString(R.string.shazam_developer_token)
        return DeveloperToken(token)
    }
}
