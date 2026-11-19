import ExpoModulesCore

internal class SearchInProgressException: Exception {
  override var reason: String {
    "Search is already in progress. Please cancel current search and try again"
  }
}

internal class NoMatchException: Exception {
    override var reason: String {
        "Could not find a match"
    }
}

internal class FailedToStartAudioEngine: Exception {
  override var reason: String {
    "Audio engine could not be started. Try again"
  }
}

internal class MicrophonePermissionDeniedException: Exception {
  override var reason: String {
    "Microphone access was not granted"
  }
}

internal class NoAudioInputException: Exception {
  override var reason: String {
    "No microphone input is available"
  }
}
