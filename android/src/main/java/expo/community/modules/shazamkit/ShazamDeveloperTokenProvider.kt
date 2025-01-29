// ShazamDeveloperTokenProvider.kt
package expo.community.modules.shazamkit

import android.content.Context
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import expo.community.modules.shazamkit.R

class ShazamDeveloperTokenProvider(private val context: Context) : DeveloperTokenProvider {
  override fun provideDeveloperToken(): DeveloperToken {
    val token = context.getString(R.string.shazam_developer_token)
    return DeveloperToken(token)
  }
}
