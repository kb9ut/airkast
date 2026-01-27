package com.example.airkast

import java.io.IOException

sealed class ApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

class Auth1Exception(message: String = "Authentication (step 1) failed", cause: Throwable? = null) : ApiException(message, cause)
class Auth2Exception(message: String = "Authentication (step 2) failed", cause: Throwable? = null) : ApiException(message, cause)
class PartialKeyException(message: String = "Failed to get partial key", cause: Throwable? = null) : ApiException(message, cause)
class StationListException(message: String = "Failed to get station list", cause: Throwable? = null) : ApiException(message, cause)
class ProgramGuideException(message: String = "Failed to get program guide", cause: Throwable? = null) : ApiException(message, cause)
class HlsStreamException(message: String = "Failed to get HLS stream URL", cause: Throwable? = null) : ApiException(message, cause)
class HlsDownloadException(message: String = "Failed to download HLS stream", cause: Throwable? = null) : ApiException(message, cause)
class XmlParseException(message: String = "Failed to parse XML", cause: Throwable? = null) : ApiException(message, cause)
