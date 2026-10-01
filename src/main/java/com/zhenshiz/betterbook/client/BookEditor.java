package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.viscript_lib.gui.editor.EditorServerUploads;
import com.viscript_lib.gui.editor.EditorUploadAction;
import com.viscript_lib.gui.editor.FunctionFileEditor;
import com.zhenshiz.betterbook.BetterBook;

import java.io.File;
import java.nio.file.Files;

/** VSL 编辑器壳；保存成功后才推进关闭和文件切换回调。 */
public final class BookEditor extends FunctionFileEditor {
    public BookEditor() {
        removeBottomWindow();
        registerProjectType(BookProject.TYPE);
    }

    @Override
    protected void onPrepareInspectorView() {
        // 书籍信息由专用 View 直接编辑。
    }

    @Override
    protected void onPrepareHistoryView() {
        // 内容历史由 BookSession 管理，不展示 VSL 工程历史面板。
    }

    @Override
    protected void loadNewProject(
            com.lowdragmc.lowdraglib2.editor.project.IProject project, File file) {
        var behavior = com.lowdragmc.lowdraglib2.editor.settings.BehaviorSettings.of(this);
        boolean restore = behavior.isRestoreLayoutOnProjectOpen();
        behavior.setRestoreLayoutOnProjectOpen(false);
        try {
            super.loadNewProject(project, file);
        } finally {
            behavior.setRestoreLayoutOnProjectOpen(restore);
        }
    }

    @Override
    protected void addExportLeaf(com.lowdragmc.lowdraglib2.gui.util.TreeBuilder.Menu menu) {
        // HTML 在源码页直接复制粘贴；不添加单独的导入导出入口。
    }

    @Override
    protected EditorUploadAction createServerUploadAction() {
        var project = book();
        if (project == null) return null;
        return new EditorUploadAction() {
            @Override
            public String getDefaultFileName() {
                if (project.serverPath != null) return project.serverPath;
                var file = getCurrentProjectFile();
                return file == null ? "new.book" : file.getName();
            }

            @Override
            public String getSuffix() {
                return BookProject.FORMAT.runtimeSuffix();
            }

            @Override
            public String normalizeFileName(String fileName) {
                if (project.serverPath == null)
                    return EditorUploadAction.super.normalizeFileName(fileName);
                return com.viscript_lib.gui.editor.EditorAssetFiles.normalizeRelativePath(fileName);
            }

            @Override
            public void uploadToServer(String fileName) {
                project.session.applySources();
                if (project.serverPath != null) {
                    com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor.rpcToServer(
                            com.zhenshiz.betterbook.data.ServerBooks.SAVE,
                            fileName,
                            project.serializeNBT(Platform.getFrozenRegistry()));
                    return;
                }
                EditorServerUploads.uploadToServer(
                        BookProject.FORMAT,
                        fileName,
                        project.serializeNBT(Platform.getFrozenRegistry()));
            }
        };
    }

    @Override
    protected Editor createNewEditorInstance() {
        return new BookEditor();
    }

    public void newBook() {
        loadProject(BookProject.TYPE.newEmptyProject(), null);
    }

    /**
     * 打开从服务端传来的书籍，上传默认使用原服务端相对路径。
     *
     * @param book 已校验书籍
     * @param path 服务端运行时相对路径
     */
    public void openServerBook(com.zhenshiz.betterbook.core.Book book, String path) {
        var project = new BookProject();
        project.deserializeNBT(
                Platform.getFrozenRegistry(), com.zhenshiz.betterbook.data.BookFiles.encode(book));
        project.serverPath = path;
        loadProject(project, null);
    }

    public BookProject book() {
        return getCurrentProject() instanceof BookProject p ? p : null;
    }

    @Override
    protected void onExecuteCommand(UIEvent e) {
        if (book() != null
                && (CommandEvents.UNDO.equals(e.command) || CommandEvents.REDO.equals(e.command))) {
            if (CommandEvents.UNDO.equals(e.command)) book().session.undo();
            else book().session.redo();
            e.stopPropagation();
            return;
        }
        super.onExecuteCommand(e);
    }

    @Override
    protected void onValidateCommand(UIEvent e) {
        if (book() != null
                && (CommandEvents.UNDO.equals(e.command) || CommandEvents.REDO.equals(e.command)))
            e.stopPropagation();
        else super.onValidateCommand(e);
    }

    @Override
    public void saveProject(Runnable finish) {
        saveProject(finish, true);
    }

    @Override
    public void saveProject(Runnable finish, boolean notification) {
        if (book() == null) return;
        if (getCurrentProjectFile() == null) {
            saveAsProject(finish);
            return;
        }
        save(getCurrentProjectFile(), finish, notification);
    }

    @Override
    public void saveAsProject(Runnable finish) {
        saveAsProject(getCurrentProjectFile(), finish);
    }

    @Override
    public void saveAsProject(File initial, Runnable finish) {
        if (book() == null) return;
        try {
            book().session.applySources();
            Files.createDirectories(BookProject.FORMAT.functionDirectory().toPath());
        } catch (Exception e) {
            error(e);
            return;
        }
        var dialog = new Dialog().setTitle("gui.betterbook.save_as");
        dialog.overlay.getLayout().width(280);
        var input =
                new TextField().setValue(initial == null ? "new.book" : initial.getAbsolutePath());
        input.setId("book-save-path");
        input.getLayout().widthPercent(100);
        dialog.addContent(new Label().setText("gui.betterbook.save_path"));
        dialog.addContent(input);
        dialog.addButton(
                new Button()
                        .setText("gui.betterbook.save")
                        .setOnClick(
                                e -> {
                                    String name = input.getValue().trim();
                                    if (name.isEmpty()) return;
                                    File file = new File(name);
                                    if (!file.isAbsolute())
                                        file =
                                                new File(
                                                        BookProject.FORMAT.functionDirectory(),
                                                        name);
                                    if (!file.getName().endsWith(".book"))
                                        file =
                                                new File(
                                                        file.getParentFile(),
                                                        file.getName() + ".book");
                                    final File target = file;
                                    Runnable write =
                                            () -> {
                                                if (save(target, finish, true)) dialog.close();
                                            };
                                    if (target.exists() && !target.equals(getCurrentProjectFile()))
                                        Dialog.showCheckBox(
                                                        "gui.betterbook.overwrite",
                                                        "gui.betterbook.overwrite_message",
                                                        yes -> {
                                                            if (yes) write.run();
                                                        })
                                                .show(getModularUI());
                                    else write.run();
                                })
                        .setId("book-save-confirm"));
        dialog.addButton(
                new Button().setText("gui.betterbook.cancel").setOnClick(e -> dialog.close()));
        dialog.show(getModularUI());
    }

    private boolean save(File file, Runnable finish, boolean notification) {
        try {
            BookProject.TYPE.saveProjectToFile(book(), file);
            currentProjectFile = file;
            recordRecentProject();
            if (notification)
                Dialog.showNotification("gui.betterbook.saved", 2).show(getModularUI());
            if (finish != null) finish.run();
            return true;
        } catch (Exception e) {
            error(e);
            return false;
        }
    }

    public void error(Exception e) {
        BetterBook.LOGGER.warn("Book operation failed", e);
        Dialog.showNotification(
                        "gui.betterbook.error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        null)
                .show(getModularUI());
    }
}
