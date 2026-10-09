package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.project.*;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.viscript_lib.gui.editor.*;
import com.zhenshiz.betterbook.api.ExtensionContext;
import com.zhenshiz.betterbook.core.*;
import com.zhenshiz.betterbook.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.io.File;

/** VSL 单运行时文件项目适配器。 */
public final class BookProject implements IProject {
    public static final EditorFileFormat FORMAT = BookFiles.FORMAT;
    public static final FunctionFileProjectType TYPE =
            new FunctionFileProjectType(null, "gui.betterbook.project", FORMAT, BookProject::new) {
                @Override
                public void saveProjectToFile(IProject project, File file) throws Exception {
                    var book = (BookProject) project;
                    book.session.applySources();
                    BookFiles.write(file.toPath(), book.serializeNBT(Platform.getFrozenRegistry()));
                }

                @Override
                public boolean isProjectDirty(IProject project, File file) throws Exception {
                    var book = (BookProject) project;
                    return book.session.hasAnyDraft() || super.isProjectDirty(project, file);
                }
            };
    public final ExtensionContext extensions = BookExtensions.create();
    public BookSession session = newSession();

    private BookSession newSession() {
        var book = Book.empty(extensions.schema);
        book.defaultLanguage = Book.normalizeLanguage(Minecraft.getInstance().options.languageCode);
        return new BookSession(book);
    }

    private final Resources resources = Resources.of();
    BookWorkspace workspace;
    String serverPath;

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public ProjectType getProjectType() {
        return TYPE;
    }

    @Override
    public CompoundTag serializeProject(HolderLookup.Provider provider) {
        return BookData.from(session.book()).serializeNBT(provider);
    }

    @Override
    public void deserializeProject(HolderLookup.Provider provider, CompoundTag tag) {
        session = new BookSession(BookData.load(provider, tag).toBook(extensions.schema));
        String language = Minecraft.getInstance().options.languageCode;
        if (session.book().languages().contains(language)) session.switchLanguage(language);
    }

    @Override
    public void onLoad(Editor editor) {
        workspace = new BookWorkspace(this, (BookEditor) editor);
        workspace.mount();
    }

    @Override
    public void onClosed(Editor editor) {
        if (workspace != null) {
            workspace.close();
            workspace = null;
        }
    }
}
