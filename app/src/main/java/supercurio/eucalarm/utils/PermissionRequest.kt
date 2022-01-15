package supercurio.eucalarm.utils

data class PermissionRequestParams(
    val permission: String,
    val explanation: Int,
    val permissionDeniedExplanation: Int? = null,
)
