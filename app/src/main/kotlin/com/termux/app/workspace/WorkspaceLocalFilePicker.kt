package com.termux.app.workspace

import android.app.AlertDialog
import android.content.DialogInterface
import com.termux.app.TermuxActivity
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.util.TreeSet
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate

/** Shared local picker. Only installed proot-distro roots are offered; no environment installation.*/
class WorkspaceLocalFilePicker : AutoCloseable {
    class Location {
        @JvmField val name: String
        @JvmField val environment: String
        @JvmField val path: String
        @JvmField val shared: Boolean

        constructor(n: String, e: String, p: String, s: Boolean) {
            name = n
            environment = e
            path = p
            shared = s
        }
    }

    private var chatLimit: Int = 0
    private val activity: TermuxActivity
    private val browsers: MutableList<WorkspaceFileBrowser> = ArrayList<WorkspaceFileBrowser>()
    private var chooser: AlertDialog? = null
    private var closed: Boolean = false
    private var selectionEpoch: Int = 0
    private val requestStorage: Consumer<Runnable>

    constructor(a: TermuxActivity) : this(a, Consumer<Runnable>(a::requestWorkspaceStorageAccess))

    constructor(a: TermuxActivity, storage: Consumer<Runnable>) {
        activity = a
        requestStorage = storage
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun choose(title: String, choice: WorkspaceFileBrowser.FileChoice) {
        choose(title, choice, null, null)
    }

    fun choosePhoneFile(choice: WorkspaceFileBrowser.FileChoice) {
        if (closed) {
            return
        }
        val epoch: Int = ++selectionEpoch
        requestStorage.accept(Runnable {
            if (closed || epoch != selectionEpoch) {
                return@Runnable
            }
            val source = WorkspaceStore.Session()
            source.name = t("Память устройства", "Device storage")
            source.environment = "termux"
            val browser = WorkspaceFileBrowser(activity, source, "/sdcard", null)
            browser.setRootBoundary("/sdcard")
            browser.setFileChoice(WorkspaceFileBrowser.FileChoice { profile, entry ->
                if (!closed && epoch == selectionEpoch) {
                    choice.selected(profile, entry)
                }
            })
            browsers.add(browser)
            browser.show()
        })
    }

    fun chooseChatAttachments(limit: Int, choice: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>, gallery: Runnable) {
        if (closed) {
            return
        }
        chatLimit = limit
        val epoch: Int = ++selectionEpoch
        chooser = WorkspaceDialogs.builder(activity).setTitle(t("Прикрепить файл", "Attach file")).setItems(arrayOf<String>(t("Окружения", "Environments"), t("Память телефона", "Phone storage"), t("Из галереи устройства", "From device gallery")), DialogInterface.OnClickListener { d, which ->
            if (closed || epoch != selectionEpoch) {
                return@OnClickListener
            }
            if (which == 2) {
                gallery.run()
                return@OnClickListener
            }
            if (which == 1) {
                requestStorage.accept(Runnable {
                    if (closed || epoch != selectionEpoch) {
                        return@Runnable
                    }
                    val source = WorkspaceStore.Session()
                    source.name = t("Память устройства", "Device storage")
                    source.environment = "termux"
                    val browser = WorkspaceFileBrowser(activity, source, "/sdcard", null)
                    browser.setRootBoundary("/sdcard")
                    browser.setChatFileChoices(BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>> { p, entries ->
                        if (!closed && epoch == selectionEpoch) {
                            choice.accept(p, entries)
                        }
                    }, limit)
                    browsers.add(browser)
                    browser.show()
                })
            } else {
                choose(t("Выберите окружение", "Choose environment"), null, choice, null, false)
            }
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        chooser!!.show()
    }

    fun chooseChatAttachment(choice: WorkspaceFileBrowser.FileChoice) {
        if (closed) {
            return
        }
        val epoch: Int = ++selectionEpoch
        chooser = WorkspaceDialogs.builder(activity).setTitle(t("Прикрепить файл", "Attach file")).setItems(arrayOf<String>(t("Окружения", "Environments"), t("Память телефона", "Phone storage")), DialogInterface.OnClickListener { d, which ->
            if (closed || epoch != selectionEpoch) {
                return@OnClickListener
            }
            if (which == 1) {
                choosePhoneFile(choice)
            } else {
                choose(t("Выберите окружение", "Choose environment"), choice, null, null, false)
            }
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        chooser!!.show()
    }

    fun chooseDirectory(title: String, choice: BiConsumer<WorkspaceStore.Session, String?>) {
        choose(title, null, null, choice)
    }

    fun chooseMultiple(title: String, choice: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>) {
        chatLimit = 0
        choose(title, null, choice, null)
    }

    private fun choose(title: String, choice: WorkspaceFileBrowser.FileChoice?, multiple: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>?, directory: BiConsumer<WorkspaceStore.Session, String?>?) {
        choose(title, choice, multiple, directory, true)
    }

    private fun choose(title: String, choice: WorkspaceFileBrowser.FileChoice?, multiple: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>?, directory: BiConsumer<WorkspaceStore.Session, String?>?, includePhone: Boolean) {
        if (closed) {
            return
        }
        val epoch: Int = ++selectionEpoch
        val locations: MutableList<Location> = locations(File(TermuxConstants.TERMUX_HOME_DIR_PATH), File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), t("Память устройства", "Device storage"))
        if (!includePhone) {
            locations.removeIf(Predicate<Location> { location -> location.shared })
        }
        val names = arrayOfNulls<String>(locations.size)
        for (i in 0 until (names.size)) {
            names[i] = locations.get(i).name
        }
        chooser = WorkspaceDialogs.builder(activity).setTitle(title).setItems(names, DialogInterface.OnClickListener { d, which ->
            val location: Location = locations.get(which)
            val open = Runnable {
                if (closed || epoch != selectionEpoch) {
                    return@Runnable
                }
                val profile = WorkspaceStore.Session()
                profile.name = location.name
                profile.environment = location.environment
                val browser = WorkspaceFileBrowser(activity, profile, location.path, if (directory == null) null else WorkspaceFileBrowser.DirectoryChoice { path ->
                    if (!closed && epoch == selectionEpoch) {
                        directory.accept(profile, path)
                    }
                })
                if (multiple != null) {
                    if (chatLimit > 0) {
                        browser.setChatFileChoices(BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>> { source, entries ->
                            if (!closed && epoch == selectionEpoch) {
                                multiple.accept(source, entries)
                            }
                        }, chatLimit)
                    } else {
                        browser.setFileChoices(BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>> { source, entries ->
                            if (!closed && epoch == selectionEpoch) {
                                multiple.accept(source, entries)
                            }
                        })
                    }
                } else if (choice != null) {
                    browser.setFileChoice(WorkspaceFileBrowser.FileChoice { source, entry ->
                        if (!closed && epoch == selectionEpoch) {
                            choice.selected(source, entry)
                        }
                    })
                }
                browsers.add(browser)
                browser.show()
            }
            if (location.shared) {
                requestStorage.accept(open)
            } else {
                open.run()
            }
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        chooser!!.show()
    }

    fun cancelSelection() {
        selectionEpoch++
        if (chooser != null) {
            chooser!!.dismiss()
        }
        for (browser in ArrayList<WorkspaceFileBrowser>(browsers)) {
            browser.close()
        }
        browsers.clear()
    }

    override fun close() {
        closed = true
        cancelSelection()
    }

    companion object {
        @JvmStatic
        fun validAlias(alias: String?): Boolean {
            return alias != null && (alias as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        }

        @JvmStatic
        fun locations(home: File, proot: File, storageLabel: String): MutableList<Location> {
            val result: MutableList<Location> = ArrayList<Location>()
            result.add(Location("Termux", "termux", home.getPath(), false))
            val aliases: MutableSet<String> = TreeSet<String>()
            for (name in arrayOf<String>("installed-rootfs", "containers")) {
                val roots: Array<File>? = File(proot, name).listFiles()
                if (roots == null) {
                    continue
                }
                for (root in roots) {
                    val fs: File = if (name.equals("containers")) File(root, "rootfs") else root
                    try {
                        if (validAlias(root.getName()) && fs.isDirectory() && root.getCanonicalFile().equals(File(root.getParentFile()!!.getCanonicalFile(), root.getName())) && fs.getCanonicalFile().equals(File(fs.getParentFile()!!.getCanonicalFile(), fs.getName()))) {
                            aliases.add(root.getName())
                        }
                    } catch (ignored: IOException) {
                    }
                }
            }
            for (alias in aliases) {
                result.add(Location(if (alias.equals("ubuntu")) "Ubuntu" else alias, "proot:" + alias, "/", false))
            }
            result.add(Location(storageLabel, "termux", "/sdcard", true))
            return result
        }
    }
}
