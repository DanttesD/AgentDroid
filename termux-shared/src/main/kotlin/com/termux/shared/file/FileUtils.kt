package com.termux.shared.file

import android.os.Build
import android.system.Os
import com.google.common.io.MoreFiles
import com.google.common.io.RecursiveDeleteOption
import com.termux.shared.data.DataUtils
import com.termux.shared.file.filesystem.FileType
import com.termux.shared.file.filesystem.FileTypes
import com.termux.shared.logger.Logger
import com.termux.shared.models.errors.Errno
import com.termux.shared.models.errors.Error
import com.termux.shared.models.errors.FileUtilsErrno
import com.termux.shared.models.errors.FunctionErrno
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStreamWriter
import java.io.Serializable
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.AgeFileFilter
import org.apache.commons.io.filefilter.IOFileFilter

object FileUtils {

    open class ReadSerializableObjectResult {
        @JvmField var error: Error? = null
        @JvmField var serializableObject: Serializable? = null

        constructor(error: Error?, serializableObject: Serializable?) {
            this.error = error
            this.serializableObject = serializableObject
        }
    }

    /** Required file permissions for the executable file for app usage. Executable file must have read and execute permissions*/
    const val APP_EXECUTABLE_FILE_PERMISSIONS: String = "r-x" // Default: "r-x"
    /** Required file permissions for the working directory for app usage. Working directory must have read and write permissions.
     * Execute permissions should be attempted to be set, but ignored if they are missing*/
    const val APP_WORKING_DIRECTORY_PERMISSIONS: String = "rwx" // Default: "rwx"

    private const val LOG_TAG: String = "FileUtils"

    /**
     * Get canonical path.
     *
     * If path is already an absolute path, then it is used as is to get canonical path.
     * If path is not an absolute path and {code prefixForNonAbsolutePath} is not `null`, then
     * {code prefixForNonAbsolutePath} + "/" is prefixed before path before getting canonical path.
     * If path is not an absolute path and {code prefixForNonAbsolutePath} is `null`, then
     * "/" is prefixed before path before getting canonical path.
     *
     * If an exception is raised to get the canonical path, then absolute path is returned.
     *
     * @param path The `path` to convert.
     * @param prefixForNonAbsolutePath Optional prefix path to prefix before non-absolute paths. This
     *                                 can be set to `null` if non-absolute paths should
     *                                 be prefixed with "/". The call to [File.getCanonicalPath]
     *                                 will automatically do this anyways.
     * @return Returns the `canonical path`.
     **/
    @JvmStatic
    fun getCanonicalPath(path: String?, prefixForNonAbsolutePath: String?): String? {
        var path = path
        if (path == null) {
            path = ""
        }

        var absolutePath: String?

        // If path is already an absolute path
        if (path!!.startsWith("/")) {
            absolutePath = path
        } else {
            if (prefixForNonAbsolutePath != null) {
                absolutePath = prefixForNonAbsolutePath + "/" + path
            } else {
                absolutePath = "/" + path
            }
        }

        try {
            return File(absolutePath).getCanonicalPath()
        } catch (e: Exception) {
        }

        return absolutePath
    }

    /**
     * Removes one or more forward slashes "//" with single slash "/"
     * Removes "./"
     * Removes trailing forward slash "/"
     *
     * @param path The `path` to convert.
     * @return Returns the `normalized path`.
     **/
    @JvmStatic
    fun normalizePath(path: String?): String? {
        var path = path
        if (path == null) {
            return null
        }

        path = (path as java.lang.String).replaceAll("/+", "/")
        path = (path!! as java.lang.String).replaceAll("\\./", "")

        if (path!!.endsWith("/")) {
            path = path!!.substring(0, path!!.length - 1)
        }

        return path
    }

    /**
     * Convert special characters `\/:*?"<>|` to underscore.
     *
     * @param fileName The name to sanitize.
     * @param sanitizeWhitespaces If set to `true`, then white space characters ` \t\n` will be
     *                            converted.
     * @param sanitizeWhitespaces If set to `true`, then file name will be converted to lowe case.
     * @return Returns the `sanitized name`.
     **/
    @JvmStatic
    fun sanitizeFileName(fileName: String?, sanitizeWhitespaces: Boolean, toLower: Boolean): String? {
        var fileName = fileName
        if (fileName == null) {
            return null
        }

        if (sanitizeWhitespaces) {
            fileName = (fileName as java.lang.String).replaceAll("[\\\\/:*?\"<>| \t\n]", "_")
        } else {
            fileName = (fileName!! as java.lang.String).replaceAll("[\\\\/:*?\"<>|]", "_")
        }

        if (toLower) {
            return (fileName!! as java.lang.String).toLowerCase(Locale.ROOT)
        } else {
            return fileName
        }
    }

    /**
     * Determines whether path is in `dirPath`. The `dirPath` is not canonicalized and
     * only normalized.
     *
     * @param path The `path` to check.
     * @param dirPath The `directory path` to check in.
     * @param ensureUnder If set to `true`, then it will be ensured that `path` is
     *                    under the directory and does not equal it.
     * @return Returns `true` if path in `dirPath`, otherwise returns `false`.
     **/
    @JvmStatic
    fun isPathInDirPath(path: String, dirPath: String, ensureUnder: Boolean): Boolean {
        return isPathInDirPaths(path, Collections.singletonList(dirPath), ensureUnder)
    }

    /**
     * Determines whether path is in one of the `dirPaths`. The `dirPaths` are not
     * canonicalized and only normalized.
     *
     * @param path The `path` to check.
     * @param dirPaths The `directory paths` to check in.
     * @param ensureUnder If set to `true`, then it will be ensured that `path` is
     *                    under the directories and does not equal it.
     * @return Returns `true` if path in `dirPaths`, otherwise returns `false`.
     **/
    @JvmStatic
    fun isPathInDirPaths(path: String?, dirPaths: MutableList<String>?, ensureUnder: Boolean): Boolean {
        var path = path
        if (path == null || path.isEmpty() || dirPaths == null || dirPaths.size < 1) {
            return false
        }

        try {
            path = File(path).getCanonicalPath()
        } catch (e: Exception) {
            return false
        }

        var isPathInDirPaths: Boolean

        for (dirPath in dirPaths) {
            val normalizedDirPath: String? = normalizePath(dirPath)

            if (ensureUnder) {
                isPathInDirPaths = !path!!.equals(normalizedDirPath) && path!!.startsWith(normalizedDirPath + "/")
            } else {
                isPathInDirPaths = path!!.startsWith(normalizedDirPath + "/")
            }

            if (isPathInDirPaths) {
                return true
            }
        }

        return false
    }

    /**
     * Checks whether a regular file exists at `filePath`.
     *
     * @param filePath The `path` for regular file to check.
     * @param followLinks The `boolean` that decides if symlinks will be followed while
     *                       finding if file exists. Check [.getFileType]
     *                       for details.
     * @return Returns `true` if regular file exists, otherwise `false`.
     **/
    @JvmStatic
    fun regularFileExists(filePath: String, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) === FileType.REGULAR
    }

    /**
     * Checks whether a directory file exists at `filePath`.
     *
     * @param filePath The `path` for directory file to check.
     * @param followLinks The `boolean` that decides if symlinks will be followed while
     *                       finding if file exists. Check [.getFileType]
     *                       for details.
     * @return Returns `true` if directory file exists, otherwise `false`.
     **/
    @JvmStatic
    fun directoryFileExists(filePath: String, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) === FileType.DIRECTORY
    }

    /**
     * Checks whether a symlink file exists at `filePath`.
     *
     * @param filePath The `path` for symlink file to check.
     * @return Returns `true` if symlink file exists, otherwise `false`.
     **/
    @JvmStatic
    fun symlinkFileExists(filePath: String): Boolean {
        return getFileType(filePath, false) === FileType.SYMLINK
    }

    /**
     * Checks whether any file exists at `filePath`.
     *
     * @param filePath The `path` for file to check.
     * @param followLinks The `boolean` that decides if symlinks will be followed while
     *                       finding if file exists. Check [.getFileType]
     *                       for details.
     * @return Returns `true` if file exists, otherwise `false`.
     **/
    @JvmStatic
    fun fileExists(filePath: String, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) !== FileType.NO_EXIST
    }

    /**
     * Checks the type of file that exists at `filePath`.
     *
     * This function is a wrapper for
     * [FileTypes.getFileType]
     *
     * @param filePath The `path` for file to check.
     * @param followLinks The `boolean` that decides if symlinks will be followed while
     *                       finding type. If set to `true`, then type of symlink target will
     *                       be returned if file at `filePath` is a symlink. If set to
     *                       `false`, then type of file at `filePath` itself will be
     *                       returned.
     * @return Returns the [FileType] of file.
     **/
    @JvmStatic
    fun getFileType(filePath: String, followLinks: Boolean): FileType {
        return FileTypes.getFileType(filePath, followLinks)
    }

    /**
     * Validate the existence and permissions of regular file at path.
     *
     * If the `parentDirPath` is not `null`, then setting of missing permissions will
     * only be done if `path` is under `parentDirPath`.
     *
     * @param label The optional label for the regular file. This can optionally be `null`.
     * @param filePath The `path` for file to validate. Symlinks will not be followed.
     * @param parentDirPath The optional `parent directory path` to restrict operations to.
     *                      This can optionally be `null`. It is not canonicalized and only normalized.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The `boolean` that decides if permissions are to be
     *                              automatically set defined by `permissionsToCheck`.
     * @param setMissingPermissionsOnly The `boolean` that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsUnderParentDirPath The `boolean` that decides if permission
     *                                               errors are to be ignored if path is under
     *                                               `parentDirPath`.
     * @return Returns the `error` if path is not a regular file, or validating permissions
     * failed, otherwise `null`.
     **/
    @JvmStatic
    fun validateRegularFileExistenceAndPermissions(label: String?, filePath: String?, parentDirPath: String?, permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean, ignoreErrorsIfPathIsUnderParentDirPath: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "regular file path", "validateRegularFileExistenceAndPermissions")
        }

        try {
            val fileType: FileType = getFileType(filePath, false)

            // If file exists but not a regular file
            if (fileType !== FileType.NO_EXIST && fileType !== FileType.REGULAR) {
                return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
            }

            var isPathUnderParentDirPath = false
            if (parentDirPath != null) {
                // The path can only be under parent directory path
                isPathUnderParentDirPath = isPathInDirPath(filePath, parentDirPath, true)
            }

            // If setPermissions is enabled and path is a regular file
            if (setPermissions && permissionsToCheck != null && fileType === FileType.REGULAR) {
                // If there is not parentDirPath restriction or path is under parentDirPath
                if (parentDirPath == null || isPathUnderParentDirPath && getFileType(parentDirPath, false) === FileType.DIRECTORY) {
                    if (setMissingPermissionsOnly) {
                        setMissingFilePermissions(label + "file", filePath, permissionsToCheck)
                    } else {
                        setFilePermissions(label + "file", filePath, permissionsToCheck)
                    }
                }
            }

            // If path is not a regular file
            // Regular files cannot be automatically created so we do not ignore if missing
            if (fileType !== FileType.REGULAR) {
                label += "regular file"
                return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
            }

            // If there is not parentDirPath restriction or path is not under parentDirPath or
            // if permission errors must not be ignored for paths under parentDirPath
            if (parentDirPath == null || !isPathUnderParentDirPath || !ignoreErrorsIfPathIsUnderParentDirPath) {
                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions(label + "regular", filePath, permissionsToCheck, false)
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_VALIDATE_FILE_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        }

        return null
    }

    /**
     * Validate the existence and permissions of directory file at path.
     *
     * If the `parentDirPath` is not `null`, then creation of missing directory and
     * setting of missing permissions will only be done if `path` is under
     * `parentDirPath` or equals `parentDirPath`.
     *
     * @param label The optional label for the directory file. This can optionally be `null`.
     * @param filePath The `path` for file to validate or create. Symlinks will not be followed.
     * @param parentDirPath The optional `parent directory path` to restrict operations to.
     *                      This can optionally be `null`. It is not canonicalized and only normalized.
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     *                                 should be created if its missing.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The `boolean` that decides if permissions are to be
     *                              automatically set defined by `permissionsToCheck`.
     * @param setMissingPermissionsOnly The `boolean` that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsInParentDirPath The `boolean` that decides if existence
     *                                  and permission errors are to be ignored if path is
     *                                  in `parentDirPath`.
     * @param ignoreIfNotExecutable The `boolean` that decides if missing executable permission
     *                              error is to be ignored. This allows making an attempt to set
     *                              executable permissions, but ignoring if it fails.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun validateDirectoryFileExistenceAndPermissions(label: String?, filePath: String?, parentDirPath: String?, createDirectoryIfMissing: Boolean, permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean, ignoreErrorsIfPathIsInParentDirPath: Boolean, ignoreIfNotExecutable: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "directory file path", "validateDirectoryExistenceAndPermissions")
        }

        try {
            val file = File(filePath)
            var fileType: FileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType !== FileType.NO_EXIST && fileType !== FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            var isPathInParentDirPath = false
            if (parentDirPath != null) {
                // The path can be equal to parent directory path or under it
                isPathInParentDirPath = isPathInDirPath(filePath, parentDirPath, false)
            }

            if (createDirectoryIfMissing || setPermissions) {
                // If there is not parentDirPath restriction or path is in parentDirPath
                if (parentDirPath == null || isPathInParentDirPath && getFileType(parentDirPath, false) === FileType.DIRECTORY) {
                    // If createDirectoryIfMissing is enabled and no file exists at path, then create directory
                    if (createDirectoryIfMissing && fileType === FileType.NO_EXIST) {
                        Logger.logVerbose(LOG_TAG, "Creating " + label + "directory file at path \"" + filePath + "\"")
                        // Create directory and update fileType if successful, otherwise return with error
                        // It "might" be possible that mkdirs returns false even though directory was created
                        val result: Boolean = file.mkdirs()
                        fileType = getFileType(filePath, false)
                        if (!result && fileType !== FileType.DIRECTORY) {
                            return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError(label + "directory file", filePath)
                        }
                    }

                    // If setPermissions is enabled and path is a directory
                    if (setPermissions && permissionsToCheck != null && fileType === FileType.DIRECTORY) {
                        if (setMissingPermissionsOnly) {
                            setMissingFilePermissions(label + "directory", filePath, permissionsToCheck)
                        } else {
                            setFilePermissions(label + "directory", filePath, permissionsToCheck)
                        }
                    }
                }
            }

            // If there is not parentDirPath restriction or path is not in parentDirPath or
            // if existence or permission errors must not be ignored for paths in parentDirPath
            if (parentDirPath == null || !isPathInParentDirPath || !ignoreErrorsIfPathIsInParentDirPath) {
                // If path is not a directory
                // Directories can be automatically created so we can ignore if missing with above check
                if (fileType !== FileType.DIRECTORY) {
                    label += "directory"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }

                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions(label + "directory", filePath, permissionsToCheck, ignoreIfNotExecutable)
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_VALIDATE_DIRECTORY_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION.getError(e, label + "directory file", filePath, e.message)
        }

        return null
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param filePath The `path` for regular file to create.
     * @return Returns the `error` if path is not a regular file or failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createRegularFile(filePath: String): Error? {
        return createRegularFile(null, filePath)
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param label The optional label for the regular file. This can optionally be `null`.
     * @param filePath The `path` for regular file to create.
     * @return Returns the `error` if path is not a regular file or failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createRegularFile(label: String?, filePath: String): Error? {
        return createRegularFile(label, filePath, null, false, false)
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * [.validateRegularFileExistenceAndPermissions].
     *
     * @param label The optional label for the regular file. This can optionally be `null`.
     * @param filePath The `path` for regular file to create.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The `boolean` that decides if permissions are to be
     *                              automatically set defined by `permissionsToCheck`.
     * @param setMissingPermissionsOnly The `boolean` that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @return Returns the `error` if path is not a regular file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun createRegularFile(label: String?, filePath: String?, permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "createRegularFile")
        }

        var error: Error?

        val file = File(filePath)
        val fileType: FileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType !== FileType.NO_EXIST && fileType !== FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If regular file already exists
        if (fileType === FileType.REGULAR) {
            return null
        }

        // Create the file parent directory
        error = createParentDirectoryFile(label + "regular file parent", filePath)
        if (error != null) {
            return error
        }

        try {
            Logger.logVerbose(LOG_TAG, "Creating " + label + "regular file at path \"" + filePath + "\"")

            if (!file.createNewFile()) {
                return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError(label + "regular file", filePath)
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "regular file", filePath, e.message)
        }

        return validateRegularFileExistenceAndPermissions(label, filePath, null, permissionsToCheck, setPermissions, setMissingPermissionsOnly, false)
    }

    /**
     * Create parent directory of file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param label The optional label for the parent directory file. This can optionally be `null`.
     * @param filePath The `path` for file whose parent needs to be created.
     * @return Returns the `error` if parent path is not a directory file or failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createParentDirectoryFile(label: String, filePath: String?): Error? {
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "createParentDirectoryFile")
        }

        val file = File(filePath)
        val fileParentPath: String? = file.getParent()

        if (fileParentPath != null) {
            return createDirectoryFile(label, fileParentPath, null, false, false)
        } else {
            return null
        }
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param filePath The `path` for directory file to create.
     * @return Returns the `error` if path is not a directory file or failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createDirectoryFile(filePath: String): Error? {
        return createDirectoryFile(null, filePath)
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param label The optional label for the directory file. This can optionally be `null`.
     * @param filePath The `path` for directory file to create.
     * @return Returns the `error` if path is not a directory file or failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createDirectoryFile(label: String?, filePath: String): Error? {
        return createDirectoryFile(label, filePath, null, false, false)
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * [.validateDirectoryFileExistenceAndPermissions].
     *
     * @param label The optional label for the directory file. This can optionally be `null`.
     * @param filePath The `path` for directory file to create.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The `boolean` that decides if permissions are to be
     *                              automatically set defined by `permissionsToCheck`.
     * @param setMissingPermissionsOnly The `boolean` that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun createDirectoryFile(label: String?, filePath: String, permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean): Error? {
        return validateDirectoryFileExistenceAndPermissions(label, filePath, null, true, permissionsToCheck, setPermissions, setMissingPermissionsOnly, false, false)
    }

    /**
     * Create a symlink file at path.
     *
     * This function is a wrapper for
     * [.createSymlinkFile].
     *
     * Dangling symlinks will be allowed.
     * Symlink destination will be overwritten if it already exists but only if its a symlink.
     *
     * @param targetFilePath The `path` TO which the symlink file will be created.
     * @param destFilePath The `path` AT which the symlink file will be created.
     * @return Returns the `error` if path is not a symlink file, failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createSymlinkFile(targetFilePath: String, destFilePath: String): Error? {
        return createSymlinkFile(null, targetFilePath, destFilePath, true, true, true)
    }

    /**
     * Create a symlink file at path.
     *
     * This function is a wrapper for
     * [.createSymlinkFile].
     *
     * Dangling symlinks will be allowed.
     * Symlink destination will be overwritten if it already exists but only if its a symlink.
     *
     * @param label The optional label for the symlink file. This can optionally be `null`.
     * @param targetFilePath The `path` TO which the symlink file will be created.
     * @param destFilePath The `path` AT which the symlink file will be created.
     * @return Returns the `error` if path is not a symlink file, failed to create it,
     * otherwise `null`.
     **/
    @JvmStatic
    fun createSymlinkFile(label: String, targetFilePath: String, destFilePath: String): Error? {
        return createSymlinkFile(label, targetFilePath, destFilePath, true, true, true)
    }

    /**
     * Create a symlink file at path.
     *
     * @param label The optional label for the symlink file. This can optionally be `null`.
     * @param targetFilePath The `path` TO which the symlink file will be created.
     * @param destFilePath The `path` AT which the symlink file will be created.
     * @param allowDangling The `boolean` that decides if it should be considered an
     *                              error if source file doesn't exist.
     * @param overwrite The `boolean` that decides if destination file should be overwritten if
     *                  it already exists. If set to `true`, then destination file will be
     *                  deleted before symlink is created.
     * @param overwriteOnlyIfDestIsASymlink The `boolean` that decides if overwrite should
     *                                         only be done if destination file is also a symlink.
     * @return Returns the `error` if path is not a symlink file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun createSymlinkFile(label: String?, targetFilePath: String?, destFilePath: String?, allowDangling: Boolean, overwrite: Boolean, overwriteOnlyIfDestIsASymlink: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (targetFilePath == null || targetFilePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "target file path", "createSymlinkFile")
        }
        if (destFilePath == null || destFilePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "destination file path", "createSymlinkFile")
        }

        var error: Error?

        try {
            val destFile = File(destFilePath)

            var targetFileAbsolutePath: String = targetFilePath
            // If target path is relative instead of absolute
            if (!targetFilePath.startsWith("/")) {
                val destFileParentPath: String? = destFile.getParent()
                if (destFileParentPath != null) {
                    targetFileAbsolutePath = destFileParentPath + "/" + targetFilePath
                }
            }

            val targetFileType: FileType = getFileType(targetFileAbsolutePath, false)
            val destFileType: FileType = getFileType(destFilePath, false)

            // If target file does not exist
            if (targetFileType === FileType.NO_EXIST) {
                // If dangling symlink should not be allowed, then return with error
                if (!allowDangling) {
                    label += "symlink target file"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, targetFileAbsolutePath).setLabel(label)
                }
            }

            // If destination exists
            if (destFileType !== FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestIsASymlink is enabled but destination file is not a symlink
                if (overwriteOnlyIfDestIsASymlink && destFileType !== FileType.SYMLINK) {
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_NON_SYMLINK_FILE_TYPE.getError(label + " file", destFilePath, targetFilePath, destFileType.getName())
                }

                // Delete the destination file
                error = deleteFile(label + "symlink destination", destFilePath, true)
                if (error != null) {
                    return error
                }
            } else {
                // Create the destination file parent directory
                error = createParentDirectoryFile(label + "symlink destination file parent", destFilePath)
                if (error != null) {
                    return error
                }
            }

            // create a symlink at destFilePath to targetFilePath
            Logger.logVerbose(LOG_TAG, "Creating " + label + "symlink file at path \"" + destFilePath + "\" to \"" + targetFilePath + "\"")
            Os.symlink(targetFilePath, destFilePath)
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CREATING_SYMLINK_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "symlink file", destFilePath, targetFilePath, e.message)
        }

        return null
    }

    /**
     * Copy a regular file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a regular
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to copy.
     * @param destFilePath The `destination path` for file to copy.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the `error` if copy was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun copyRegularFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, false, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(), true, true)
    }

    /**
     * Move a regular file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a regular
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to move.
     * @param destFilePath The `destination path` for file to move.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the `error` if move was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun moveRegularFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, true, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(), true, true)
    }

    /**
     * Copy a directory file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a directory
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to copy.
     * @param destFilePath The `destination path` for file to copy.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the `error` if copy was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun copyDirectoryFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, false, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(), true, true)
    }

    /**
     * Move a directory file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a directory
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to move.
     * @param destFilePath The `destination path` for file to move.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the `error` if move was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun moveDirectoryFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, true, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(), true, true)
    }

    /**
     * Copy a symlink file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a symlink
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to copy.
     * @param destFilePath The `destination path` for file to copy.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the `error` if copy was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun copySymlinkFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, false, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(), true, true)
    }

    /**
     * Move a symlink file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its a symlink
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to move.
     * @param destFilePath The `destination path` for file to move.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the `error` if move was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun moveSymlinkFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, true, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(), true, true)
    }

    /**
     * Copy a file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its the same file
     * type as the source, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to copy.
     * @param destFilePath The `destination path` for file to copy.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the `error` if copy was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun copyFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, false, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS, true, true)
    }

    /**
     * Move a file from `sourceFilePath` to `destFilePath`.
     *
     * This function is a wrapper for
     * [.copyOrMoveFile].
     *
     * If destination file already exists, then it will be overwritten, but only if its the same file
     * type as the source, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to move.
     * @param destFilePath The `destination path` for file to move.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the `error` if move was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun moveFile(label: String, srcFilePath: String, destFilePath: String, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath, true, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS, true, true)
    }

    /**
     * Copy or move a file from `sourceFilePath` to `destFilePath`.
     *
     * The `sourceFilePath` and `destFilePath` must be the canonical path to the source
     * and destination since symlinks will not be followed.
     *
     * If the `sourceFilePath` or `destFilePath` is a canonical path to a directory,
     * then any symlink files found under the directory will be deleted, but not their targets when
     * deleting source after move and deleting destination before copy/move.
     *
     * @param label The optional label for file to copy or move. This can optionally be `null`.
     * @param srcFilePath The `source path` for file to copy or move.
     * @param destFilePath The `destination path` for file to copy or move.
     * @param moveFile The `boolean` that decides if source file needs to be copied or moved.
     *                 If set to `true`, then source file will be moved, otherwise it will be
     *                 copied.
     * @param ignoreNonExistentSrcFile The `boolean` that decides if it should be considered an
     *                              error if source file to copied or moved doesn't exist.
     * @param allowedFileTypeFlags The flags that are matched against the source file's [FileType]
     *                             to see if it should be copied/moved or not. This is a safety measure
     *                             to prevent accidental copy/move/delete of the wrong type of file,
     *                             like a directory instead of a regular file. You can pass
     *                             [FileTypes.FILE_TYPE_ANY_FLAGS] to allow copy/move of any file type.
     * @param overwrite The `boolean` that decides if destination file should be overwritten if
     *                  it already exists. If set to `true`, then destination file will be
     *                  deleted before source is copied or moved.
     * @param overwriteOnlyIfDestSameFileTypeAsSrc The `boolean` that decides if overwrite should
     *                                         only be done if destination file is also the same file
     *                                          type as the source file.
     * @return Returns the `error` if copy or move was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun copyOrMoveFile(label: String?, srcFilePath: String?, destFilePath: String?, moveFile: Boolean, ignoreNonExistentSrcFile: Boolean, allowedFileTypeFlags: Int, overwrite: Boolean, overwriteOnlyIfDestSameFileTypeAsSrc: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (srcFilePath == null || srcFilePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "source file path", "copyOrMoveFile")
        }
        if (destFilePath == null || destFilePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "destination file path", "copyOrMoveFile")
        }

        val mode: String = if (moveFile) "Moving" else "Copying"
        val modePast: String = if (moveFile) "moved" else "copied"

        var error: Error?

        try {
            Logger.logVerbose(LOG_TAG, mode + " " + label + "source file from \"" + srcFilePath + "\" to destination \"" + destFilePath + "\"")

            val srcFile = File(srcFilePath)
            val destFile = File(destFilePath)

            val srcFileType: FileType = getFileType(srcFilePath, false)
            val destFileType: FileType = getFileType(destFilePath, false)

            val srcFileCanonicalPath: String = srcFile.getCanonicalPath()
            val destFileCanonicalPath: String = destFile.getCanonicalPath()

            // If source file does not exist
            if (srcFileType === FileType.NO_EXIST) {
                // If copy or move is to be ignored if source file is not found
                if (ignoreNonExistentSrcFile) {
                    return null
                } else {
                    label += "source file"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, srcFilePath).setLabel(label)
                }
            }

            // If the file type of the source file does not exist in the allowedFileTypeFlags, then return with error
            if (allowedFileTypeFlags and srcFileType.getValue() <= 0) {
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(label + "source file meant to be " + modePast, srcFilePath, FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))
            }

            // If source and destination file path are the same
            if (srcFileCanonicalPath.equals(destFileCanonicalPath)) {
                return FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_TO_SAME_PATH.getError(mode + " " + label + "source file", srcFilePath, destFilePath)
            }

            // If destination exists
            if (destFileType !== FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestSameFileTypeAsSrc is enabled but destination file does not match source file type
                if (overwriteOnlyIfDestSameFileTypeAsSrc && destFileType !== srcFileType) {
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_DIFFERENT_FILE_TYPE.getError(label + "source file", (mode as java.lang.String).toLowerCase(Locale.ROOT), srcFilePath, destFilePath, destFileType.getName(), srcFileType.getName())
                }

                // Delete the destination file
                error = deleteFile(label + "destination file", destFilePath, true)
                if (error != null) {
                    return error
                }
            }

            // Copy or move source file to dest
            var copyFile: Boolean = !moveFile

            // If moveFile is true
            if (moveFile) {
                // We first try to rename source file to destination file to save a copy operation in case both source and destination are on the same filesystem
                Logger.logVerbose(LOG_TAG, "Attempting to rename source to destination.")

                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/java/io/UnixFileSystem.java;l=358
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/luni/src/main/java/android/system/Os.java;l=512
                // Uses File.getPath() to get the path of source and destination and not the canonical path
                if (!srcFile.renameTo(destFile)) {
                    // If destination directory is a subdirectory of the source directory
                    // Copying is still allowed by copyDirectory() by excluding destination directory files
                    if (srcFileType === FileType.DIRECTORY && destFileCanonicalPath.startsWith(srcFileCanonicalPath + File.separator)) {
                        return FileUtilsErrno.ERRNO_CANNOT_MOVE_DIRECTORY_TO_SUB_DIRECTORY_OF_ITSELF.getError(label + "source directory", srcFilePath, destFilePath)
                    }

                    // If rename failed, then we copy
                    Logger.logVerbose(LOG_TAG, "Renaming " + label + "source file to destination failed, attempting to copy.")
                    copyFile = true
                }
            }

            // If moveFile is false or renameTo failed while moving
            if (copyFile) {
                Logger.logVerbose(LOG_TAG, "Attempting to copy source to destination.")

                // Create the dest file parent directory
                error = createParentDirectoryFile(label + "dest file parent", destFilePath)
                if (error != null) {
                    return error
                }

                if (srcFileType === FileType.DIRECTORY) {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    FileUtils.copyDirectory(srcFile, destFile, true)
                } else if (srcFileType === FileType.SYMLINK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Files.copy(srcFile.toPath(), destFile.toPath(), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        // read the target for the source file and create a symlink at dest
                        // source file metadata will be lost
                        error = createSymlinkFile(label + "dest file", Os.readlink(srcFilePath), destFilePath)
                        if (error != null) {
                            return error
                        }
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Files.copy(srcFile.toPath(), destFile.toPath(), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                        FileUtils.copyFile(srcFile, destFile, true)
                    }
                }
            }

            // If source file had to be moved
            if (moveFile) {
                // Delete the source file since copying would have succeeded
                error = deleteFile(label + "source file", srcFilePath, true)
                if (error != null) {
                    return error
                }
            }

            Logger.logVerbose(LOG_TAG, mode + " successful.")
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_FAILED_WITH_EXCEPTION.getError(e, mode + " " + label + "file", srcFilePath, destFilePath, e.message)
        }

        return null
    }

    /**
     * Delete regular file at path.
     *
     * This function is a wrapper for [.deleteFile].
     *
     * @param label The optional label for file to delete. This can optionally be `null`.
     * @param filePath The `path` for file to delete.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the `error` if deletion was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteRegularFile(label: String, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.REGULAR.getValue())
    }

    /**
     * Delete directory file at path.
     *
     * This function is a wrapper for [.deleteFile].
     *
     * @param label The optional label for file to delete. This can optionally be `null`.
     * @param filePath The `path` for file to delete.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the `error` if deletion was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteDirectoryFile(label: String, filePath: String, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.DIRECTORY.getValue())
    }

    /**
     * Delete symlink file at path.
     *
     * This function is a wrapper for [.deleteFile].
     *
     * @param label The optional label for file to delete. This can optionally be `null`.
     * @param filePath The `path` for file to delete.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the `error` if deletion was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteSymlinkFile(label: String, filePath: String, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.SYMLINK.getValue())
    }

    /**
     * Delete regular, directory or symlink file at path.
     *
     * This function is a wrapper for [.deleteFile].
     *
     * @param label The optional label for file to delete. This can optionally be `null`.
     * @param filePath The `path` for file to delete.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the `error` if deletion was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteFile(label: String, filePath: String, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileTypes.FILE_TYPE_NORMAL_FLAGS)
    }

    /**
     * Delete file at path.
     *
     * The `filePath` must be the canonical path to the file to be deleted since symlinks will
     * not be followed.
     * If the `filePath` is a canonical path to a directory, then any symlink files found under
     * the directory will be deleted, but not their targets.
     *
     * @param label The optional label for file to delete. This can optionally be `null`.
     * @param filePath The `path` for file to delete.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @param ignoreWrongFileType The `boolean` that decides if it should be considered an
     *                              error if file type is not one from `allowedFileTypeFlags`.
     * @param allowedFileTypeFlags The flags that are matched against the file's [FileType] to
     *                             see if it should be deleted or not. This is a safety measure to
     *                             prevent accidental deletion of the wrong type of file, like a
     *                             directory instead of a regular file. You can pass
     *                             [FileTypes.FILE_TYPE_ANY_FLAGS] to allow deletion of any file type.
     * @return Returns the `error` if deletion was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean, ignoreWrongFileType: Boolean, allowedFileTypeFlags: Int): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "deleteFile")
        }

        try {
            val file = File(filePath)
            var fileType: FileType = getFileType(filePath, false)

            Logger.logVerbose(LOG_TAG, "Processing delete of " + label + "file at path \"" + filePath + "\" of type \"" + fileType.getName() + "\"")

            // If file does not exist
            if (fileType === FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                if (ignoreNonExistentFile) {
                    return null
                } else {
                    label += "file meant to be deleted"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }
            }

            // If the file type of the file does not exist in the allowedFileTypeFlags
            if (allowedFileTypeFlags and fileType.getValue() <= 0) {
                // If wrong file type is to be ignored
                if (ignoreWrongFileType) {
                    Logger.logVerbose(LOG_TAG, "Ignoring deletion of " + label + "file at path \"" + filePath + "\" not matching allowed file types: " + FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))
                    return null
                }

                // Else return with error
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(label + "file meant to be deleted", filePath, FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))
            }

            Logger.logVerbose(LOG_TAG, "Deleting " + label + "file at path \"" + filePath + "\"")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                /*
                 * Try to use {@link SecureDirectoryStream} if available for safer directory
                 * deletion, it should be available for android >= 8.0
                 * https://guava.dev/releases/24.1-jre/api/docs/com/google/common/io/MoreFiles.html#deleteRecursively-java.nio.file.Path-com.google.common.io.RecursiveDeleteOption...-
                 * https://github.com/google/guava/issues/365
                 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/sun/nio/fs/UnixSecureDirectoryStream.java
                 *
                 * MoreUtils is marked with the @Beta annotation so the API may be removed in
                 * future but has been there for a few years now.
                 *
                 * If an exception is thrown, the exception message might not contain the full errors.
                 * Individual failures get added to suppressed throwables which can be extracted
                 * from the exception object by calling `Throwable[] getSuppressed()`. So just logging
                 * the exception message and stacktrace may not be enough, the suppressed throwables
                 * need to be logged as well, which the Logger class does if they are found in the
                 * exception added to the Error that's returned by this function.
                 * https://github.com/google/guava/blob/v30.1.1/guava/src/com/google/common/io/MoreFiles.java#L775
                 **/
                //noinspection UnstableApiUsage
                MoreFiles.deleteRecursively(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
            } else {
                if (fileType === FileType.DIRECTORY) {
                    // deleteDirectory() instead of forceDelete() gets the files list first instead of walking directory tree, so seems safer
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    FileUtils.deleteDirectory(file)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    FileUtils.forceDelete(file)
                }
            }

            // If file still exists after deleting it
            fileType = getFileType(filePath, false)
            if (fileType !== FileType.NO_EXIST) {
                return FileUtilsErrno.ERRNO_FILE_STILL_EXISTS_AFTER_DELETING.getError(label + "file meant to be deleted", filePath)
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_DELETING_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        }

        return null
    }

    /**
     * Clear contents of directory at path without deleting the directory. If directory does not exist
     * it will be created automatically.
     *
     * This function is a wrapper for
     * [.clearDirectory].
     *
     * @param filePath The `path` for directory to clear.
     * @return Returns the `error` if clearing was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun clearDirectory(filePath: String): Error? {
        return clearDirectory(null, filePath)
    }

    /**
     * Clear contents of directory at path without deleting the directory. If directory does not exist
     * it will be created automatically.
     *
     * The `filePath` must be the canonical path to a directory since symlinks will not be followed.
     * Any symlink files found under the directory will be deleted, but not their targets.
     *
     * @param label The optional label for directory to clear. This can optionally be `null`.
     * @param filePath The `path` for directory to clear.
     * @return Returns the `error` if clearing was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun clearDirectory(label: String?, filePath: String?): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "clearDirectory")
        }

        var error: Error?

        try {
            Logger.logVerbose(LOG_TAG, "Clearing " + label + "directory at path \"" + filePath + "\"")

            val file = File(filePath)
            val fileType: FileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType !== FileType.NO_EXIST && fileType !== FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            // If directory exists, clear its contents
            if (fileType === FileType.DIRECTORY) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    /* If an exception is thrown, the exception message might not contain the full errors.
                     * Individual failures get added to suppressed throwables.*/
                    //noinspection UnstableApiUsage
                    MoreFiles.deleteDirectoryContents(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    FileUtils.cleanDirectory(File(filePath))
                }
            } else {
                error = createDirectoryFile(label, filePath)
                if (error != null) {
                    return error
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CLEARING_DIRECTORY_FAILED_WITH_EXCEPTION.getError(e, label + "directory", filePath, e.message)
        }

        return null
    }

    /**
     * Delete files under a directory older than x days.
     *
     * The `filePath` must be the canonical path to a directory since symlinks will not be followed.
     * Any symlink files found under the directory will be deleted, but not their targets.
     *
     * @param label The optional label for directory to clear. This can optionally be `null`.
     * @param filePath The `path` for directory to clear.
     * @param dirFilter  The optional filter to apply when finding subdirectories.
     *                   If this parameter is `null`, subdirectories will not be included in the
     *                   search. Use TrueFileFilter.INSTANCE to match all directories.
     * @param days The x amount of days before which files should be deleted. This must be `>=0`.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @param allowedFileTypeFlags The flags that are matched against the file's [FileType] to
     *                             see if it should be deleted or not. This is a safety measure to
     *                             prevent accidental deletion of the wrong type of file, like a
     *                             directory instead of a regular file. You can pass
     *                             [FileTypes.FILE_TYPE_ANY_FLAGS] to allow deletion of any file type.
     * @return Returns the `error` if deleting was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun deleteFilesOlderThanXDays(label: String?, filePath: String?, dirFilter: IOFileFilter?, days: Int, ignoreNonExistentFile: Boolean, allowedFileTypeFlags: Int): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "deleteFilesOlderThanXDays")
        }
        if (days < 0) {
            return FunctionErrno.ERRNO_INVALID_PARAMETER.getError(label + "days", "deleteFilesOlderThanXDays", " It must be >= 0.")
        }

        var error: Error?

        try {
            Logger.logVerbose(LOG_TAG, "Deleting files under " + label + "directory at path \"" + filePath + "\" older than " + days + " days")

            val file = File(filePath)
            val fileType: FileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType !== FileType.NO_EXIST && fileType !== FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            // If file does not exist
            if (fileType === FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                if (ignoreNonExistentFile) {
                    return null
                } else {
                    label += "directory under which files had to be deleted"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }
            }

            // If directory exists, delete its contents
            val calendar: Calendar = Calendar.getInstance()
            calendar.add(Calendar.DATE, -days)
            // AgeFileFilter seems to apply to symlink destination timestamp instead of symlink file itself
            val filesToDelete: MutableIterator<File> = FileUtils.iterateFiles(file, AgeFileFilter(calendar.getTime()), dirFilter)
            while (filesToDelete.hasNext()) {
                val subFile: File = filesToDelete.next()
                error = deleteFile(label + " directory sub", subFile.getAbsolutePath(), true, true, allowedFileTypeFlags)
                if (error != null) {
                    return error
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_DELETING_FILES_OLDER_THAN_X_DAYS_FAILED_WITH_EXCEPTION.getError(e, label + "directory", filePath, days, e.message)
        }

        return null
    }

    /**
     * Read a [String] from file at path with a specific [Charset] into `dataString`.
     *
     * @param label The optional label for file to read. This can optionally be `null`.
     * @param filePath The `path` for file to read.
     * @param charset The [Charset] of the file. If this is `null`,
     *      *                then default [Charset] will be used.
     * @param dataStringBuilder The `StringBuilder` to read data into.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to read doesn't exist.
     * @return Returns the `error` if reading was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun readStringFromFile(label: String?, filePath: String?, charset: Charset?, dataStringBuilder: StringBuilder, ignoreNonExistentFile: Boolean): Error? {
        var label = label
        var charset = charset
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "readStringFromFile")
        }

        Logger.logVerbose(LOG_TAG, "Reading string from " + label + "file at path \"" + filePath + "\"")

        var error: Error?

        val fileType: FileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType !== FileType.NO_EXIST && fileType !== FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If file does not exist
        if (fileType === FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            if (ignoreNonExistentFile) {
                return null
            } else {
                label += "file meant to be read"
                return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
            }
        }

        if (charset == null) {
            charset = Charset.defaultCharset()
        }

        // Check if charset is supported
        error = isCharsetSupported(charset)
        if (error != null) {
            return error
        }

        var fileInputStream: FileInputStream? = null
        var bufferedReader: BufferedReader? = null
        try {
            // Read string from file
            fileInputStream = FileInputStream(filePath)
            bufferedReader = BufferedReader(InputStreamReader(fileInputStream, charset))

            var receiveString: String?

            var firstLine = true
            while (true) {
                receiveString = bufferedReader!!.readLine()
                if (receiveString == null) break
                if (!firstLine) {
                    dataStringBuilder.append("\n")
                } else {
                    firstLine = false
                }
                dataStringBuilder.append(receiveString)
            }

            Logger.logVerbose(LOG_TAG, Logger.getMultiLineLogStringEntry("String", DataUtils.getTruncatedCommandOutput(dataStringBuilder.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD, true, false, true), "-"))
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_READING_STRING_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(bufferedReader)
        }

        return null
    }

    /**
     * Read a [Serializable] object from file at path.
     *
     * @param label The optional label for file to read. This can optionally be `null`.
     * @param filePath The `path` for file to read.
     * @param readObjectType The [Class] of the object.
     * @param ignoreNonExistentFile The `boolean` that decides if it should be considered an
     *                              error if file to read doesn't exist.
     * @return Returns the `error` if reading was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun <T : Serializable> readSerializableObjectFromFile(label: String?, filePath: String?, readObjectType: Class<T>, ignoreNonExistentFile: Boolean): ReadSerializableObjectResult {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return ReadSerializableObjectResult(FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "readSerializableObjectFromFile"), null)
        }

        Logger.logVerbose(LOG_TAG, "Reading serializable object from " + label + "file at path \"" + filePath + "\"")

        var serializableObject: T

        val fileType: FileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType !== FileType.NO_EXIST && fileType !== FileType.REGULAR) {
            return ReadSerializableObjectResult(FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file"), null)
        }

        // If file does not exist
        if (fileType === FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            if (ignoreNonExistentFile) {
                return ReadSerializableObjectResult(null, null)
            } else {
                label += "file meant to be read"
                return ReadSerializableObjectResult(FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label), null)
            }
        }

        var fileInputStream: FileInputStream? = null
        var objectInputStream: ObjectInputStream? = null
        try {
            // Read string from file
            fileInputStream = FileInputStream(filePath)
            objectInputStream = ObjectInputStream(fileInputStream)
            //serializableObject = (T) objectInputStream.readObject();
            serializableObject = readObjectType.cast(objectInputStream!!.readObject())

            //Logger.logVerbose(LOG_TAG, Logger.getMultiLineLogStringEntry("String", DataUtils.getTruncatedCommandOutput(dataStringBuilder.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD, true, false, true), "-"));
        } catch (e: Exception) {
            return ReadSerializableObjectResult(FileUtilsErrno.ERRNO_READING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message), null)
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(objectInputStream)
        }

        return ReadSerializableObjectResult(null, serializableObject)
    }

    /**
     * Write the [String] `dataString` with a specific [Charset] to file at path.
     *
     * @param label The optional label for file to write. This can optionally be `null`.
     * @param filePath The `path` for file to write.
     * @param charset The [Charset] of the `dataString`. If this is `null`,
     *                then default [Charset] will be used.
     * @param dataString The data to write to file.
     * @param append The `boolean` that decides if file should be appended to or not.
     * @return Returns the `error` if writing was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun writeStringToFile(label: String?, filePath: String?, charset: Charset?, dataString: String?, append: Boolean): Error? {
        var label = label
        var charset = charset
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "writeStringToFile")
        }

        Logger.logVerbose(LOG_TAG, Logger.getMultiLineLogStringEntry("Writing string to " + label + "file at path \"" + filePath + "\"", DataUtils.getTruncatedCommandOutput(dataString, Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD, true, false, true), "-"))

        var error: Error?

        error = preWriteToFile(label, filePath)
        if (error != null) {
            return error
        }

        if (charset == null) {
            charset = Charset.defaultCharset()
        }

        // Check if charset is supported
        error = isCharsetSupported(charset)
        if (error != null) {
            return error
        }

        var fileOutputStream: FileOutputStream? = null
        var bufferedWriter: BufferedWriter? = null
        try {
            // Write string to file
            fileOutputStream = FileOutputStream(filePath, append)
            bufferedWriter = BufferedWriter(OutputStreamWriter(fileOutputStream, charset))

            bufferedWriter!!.write(dataString)
            bufferedWriter!!.flush()
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_WRITING_STRING_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(bufferedWriter)
        }

        return null
    }

    /**
     * Write the [Serializable] `serializableObject` to file at path.
     *
     * @param label The optional label for file to write. This can optionally be `null`.
     * @param filePath The `path` for file to write.
     * @param serializableObject The object to write to file.
     * @return Returns the `error` if writing was not successful, otherwise `null`.
     **/
    @JvmStatic
    fun <T : Serializable> writeSerializableObjectToFile(label: String?, filePath: String?, serializableObject: T): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "writeSerializableObjectToFile")
        }

        Logger.logVerbose(LOG_TAG, "Writing serializable object to " + label + "file at path \"" + filePath + "\"")

        var error: Error?

        error = preWriteToFile(label, filePath)
        if (error != null) {
            return error
        }

        var fileOutputStream: FileOutputStream? = null
        var objectOutputStream: ObjectOutputStream? = null
        try {
            // Write object to file
            fileOutputStream = FileOutputStream(filePath)
            objectOutputStream = ObjectOutputStream(fileOutputStream)

            objectOutputStream!!.writeObject(serializableObject)
            objectOutputStream!!.flush()
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_WRITING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(objectOutputStream)
        }

        return null
    }

    @JvmStatic
    private fun preWriteToFile(label: String?, filePath: String): Error? {
        var error: Error?

        val fileType: FileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType !== FileType.NO_EXIST && fileType !== FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // Create the file parent directory
        error = createParentDirectoryFile(label + "file parent", filePath)
        if (error != null) {
            return error
        }

        return null
    }

    /**
     * Check if a specific [Charset] is supported.
     *
     * @param charset The [Charset] to check.
     * @return Returns the `error` if charset is not supported or failed to check it, otherwise `null`.
     **/
    @JvmStatic
    fun isCharsetSupported(charset: Charset?): Error? {
        if (charset == null) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("charset", "isCharsetSupported")
        }

        try {
            if (!Charset.isSupported(charset.name())) {
                return FileUtilsErrno.ERRNO_UNSUPPORTED_CHARSET.getError(charset.name())
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CHECKING_IF_CHARSET_SUPPORTED_FAILED.getError(e, charset.name(), e.message)
        }

        return null
    }

    /**
     * Close a [Closeable] object if not `null` and ignore any exceptions raised.
     *
     * @param closeable The [Closeable] object to close.
     **/
    @JvmStatic
    fun closeCloseable(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    /**
     * Set permissions for file at path. Existing permission outside the `permissionsToSet`
     * will be removed.
     *
     * @param filePath The `path` for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     **/
    @JvmStatic
    fun setFilePermissions(filePath: String, permissionsToSet: String) {
        setFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set permissions for file at path. Existing permission outside the `permissionsToSet`
     * will be removed.
     *
     * @param label The optional label for the file. This can optionally be `null`.
     * @param filePath The `path` for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     **/
    @JvmStatic
    fun setFilePermissions(label: String?, filePath: String?, permissionsToSet: String) {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return
        }

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setFilePermissions: \"" + permissionsToSet + "\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet.contains("r")) {
            if (!file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Setting read permissions for " + label + "file at path \"" + filePath + "\"")
                file.setReadable(true)
            }
        } else {
            if (file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Removing read permissions for " + label + "file at path \"" + filePath + "\"")
                file.setReadable(false)
            }
        }

        if (permissionsToSet.contains("w")) {
            if (!file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Setting write permissions for " + label + "file at path \"" + filePath + "\"")
                file.setWritable(true)
            }
        } else {
            if (file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Removing write permissions for " + label + "file at path \"" + filePath + "\"")
                file.setWritable(false)
            }
        }

        if (permissionsToSet.contains("x")) {
            if (!file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Setting execute permissions for " + label + "file at path \"" + filePath + "\"")
                file.setExecutable(true)
            }
        } else {
            if (file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Removing execute permissions for " + label + "file at path \"" + filePath + "\"")
                file.setExecutable(false)
            }
        }
    }

    /**
     * Set missing permissions for file at path. Existing permission outside the `permissionsToSet`
     * will not be removed.
     *
     * @param filePath The `path` for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     **/
    @JvmStatic
    fun setMissingFilePermissions(filePath: String, permissionsToSet: String) {
        setMissingFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set missing permissions for file at path. Existing permission outside the `permissionsToSet`
     * will not be removed.
     *
     * @param label The optional label for the file. This can optionally be `null`.
     * @param filePath The `path` for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     **/
    @JvmStatic
    fun setMissingFilePermissions(label: String?, filePath: String?, permissionsToSet: String) {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return
        }

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setMissingFilePermissions: \"" + permissionsToSet + "\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet.contains("r") && !file.canRead()) {
            Logger.logVerbose(LOG_TAG, "Setting missing read permissions for " + label + "file at path \"" + filePath + "\"")
            file.setReadable(true)
        }

        if (permissionsToSet.contains("w") && !file.canWrite()) {
            Logger.logVerbose(LOG_TAG, "Setting missing write permissions for " + label + "file at path \"" + filePath + "\"")
            file.setWritable(true)
        }

        if (permissionsToSet.contains("x") && !file.canExecute()) {
            Logger.logVerbose(LOG_TAG, "Setting missing execute permissions for " + label + "file at path \"" + filePath + "\"")
            file.setExecutable(true)
        }
    }

    /**
     * Checking missing permissions for file at path.
     *
     * @param filePath The `path` for file to check permissions for.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param ignoreIfNotExecutable The `boolean` that decides if missing executable permission
     *                              error is to be ignored.
     * @return Returns the `error` if validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun checkMissingFilePermissions(filePath: String, permissionsToCheck: String, ignoreIfNotExecutable: Boolean): Error? {
        return checkMissingFilePermissions(null, filePath, permissionsToCheck, ignoreIfNotExecutable)
    }

    /**
     * Checking missing permissions for file at path.
     *
     * @param label The optional label for the file. This can optionally be `null`.
     * @param filePath The `path` for file to check permissions for.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param ignoreIfNotExecutable The `boolean` that decides if missing executable permission
     *                              error is to be ignored.
     * @return Returns the `error` if validating permissions failed, otherwise `null`.
     **/
    @JvmStatic
    fun checkMissingFilePermissions(label: String?, filePath: String?, permissionsToCheck: String, ignoreIfNotExecutable: Boolean): Error? {
        var label = label
        label = if (label == null) "" else label + " "
        if (filePath == null || filePath.isEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "checkMissingFilePermissions")
        }

        if (!isValidPermissionString(permissionsToCheck)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToCheck passed to checkMissingFilePermissions: \"" + permissionsToCheck + "\"")
            return FileUtilsErrno.ERRNO_INVALID_FILE_PERMISSIONS_STRING_TO_CHECK.getError()
        }

        val file = File(filePath)

        // If file is not readable
        if (permissionsToCheck.contains("r") && !file.canRead()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_READABLE.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If file is not writable
        if (permissionsToCheck.contains("w") && !file.canWrite()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_WRITABLE.getError(label + "file", filePath).setLabel(label + "file")
        } else if (permissionsToCheck.contains("x") && !file.canExecute() && !ignoreIfNotExecutable) {
            return FileUtilsErrno.ERRNO_FILE_NOT_EXECUTABLE.getError(label + "file", filePath).setLabel(label + "file")
        }

        return null
    }

    /**
     * Checks whether string exactly matches the 3 character permission string that
     * contains the "r", "w", "x" or "-" in-order.
     *
     * @param string The [String] to check.
     * @return Returns `true` if string exactly matches a permission string, otherwise `false`.
     **/
    @JvmStatic
    fun isValidPermissionString(string: String?): Boolean {
        if (string == null || string.isEmpty()) {
            return false
        }
        return Pattern.compile("^([r-])[w-][x-]\$", 0).matcher(string).matches()
    }

    /**
     * Get a [Error] that contains a shorter version of [Errno] message.
     *
     * @param error The original [Error] returned by one of the [FileUtils] functions.
     * @return Returns the shorter [Error] if one exists, otherwise original `error`.
     **/
    @JvmStatic
    fun getShortFileUtilsError(error: Error): Error {
        val type: String? = error.getType()
        if (!FileUtilsErrno.TYPE.equals(type)) {
            return error
        }

        val shortErrno: Errno? = FileUtilsErrno.ERRNO_SHORT_MAPPING.get(Errno.valueOf(type, error.getCode()))
        if (shortErrno == null) {
            return error
        }

        val throwables: MutableList<Throwable> = error.getThrowablesList()
        if (throwables.isEmpty()) {
            return shortErrno.getError(DataUtils.getDefaultIfNull(error.getLabel(), "file"))
        } else {
            return shortErrno.getError(throwables, error.getLabel(), "file")
        }
    }
}
