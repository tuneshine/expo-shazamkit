package expo.community.modules.shazamkit

import expo.modules.kotlin.exception.CodedException

class SearchInProgressException : CodedException("Search is already in progress. Please cancel current search and try again") {
    override fun getCode(): String {
        return "SEARCH_IN_PROGRESS"
    }
}

class NoMatchException : CodedException("No match found") {
  override fun getCode(): String {
    return "NO_MATCH"
  }
}
