package org.drools.eclipse.rulebuilder.editors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.drools.brms.client.modeldriven.SuggestionCompletionEngine;
import org.drools.brms.server.rules.SuggestionCompletionLoader;
import org.drools.brms.server.util.BRDRLPersistence;
import org.drools.brms.server.util.BRXMLPersistence;
import org.drools.eclipse.DroolsEclipsePlugin;
import org.drools.eclipse.util.ProjectClassLoader;
import org.drools.lang.dsl.DSLMappingFile;
import org.eclipse.core.internal.resources.Container;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.forms.editor.FormEditor;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.DocumentProviderRegistry;
import org.eclipse.ui.texteditor.IDocumentProvider;

public class RuleEditor extends FormEditor
    implements
    IResourceChangeListener {

    private BrxmlPage                  guidedEditor;

    private TextEditor                 xmlEditor          = new TextEditor();

    private StyledText                 drlPreviewText;

    private SuggestionCompletionEngine completion;

    private SuggestionCompletionLoader loader;

    private FileEditorInput            packageEditorInput;

    private IResourceChangeListener    packageFileTracker = new IResourceChangeListener() {

                                                              public void resourceChanged(IResourceChangeEvent event) {
                                                                  IResourceDelta delta = getRootDelta( event.getDelta() );

                                                                  IPath p1 = delta.getFullPath();
                                                                  IPath p2 = packageEditorInput.getFile().getFullPath();
                                                                  if ( p1.equals( p2 ) ) {
                                                                      reloadCompletionEngine();
                                                                  }

                                                              }

                                                          };

    public RuleEditor() {
        super();
        ResourcesPlugin.getWorkspace().addResourceChangeListener( this );
        ResourcesPlugin.getWorkspace().addResourceChangeListener( packageFileTracker,
                                                                  IResourceChangeEvent.POST_CHANGE );

    }

    protected FormToolkit createToolkit(Display display) {
        // Create a toolkit that shares colors between editors.
        return new FormToolkit( DroolsEclipsePlugin.getDefault().getRuleBuilderFormColors( display ) );
    }

    protected void addPages() {
        guidedEditor = new BrxmlPage( this );
        try {
            addPage( guidedEditor );
            addPage( xmlEditor,
                     getEditorInput() );

            addPage( initializePreviewDRLText() );

            IPath packagePath = getCurrentDirectoryPath( getEditorInput() ).append( "rule.package" );

            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile( packagePath );

            IJavaProject javaProject = JavaCore.create( file.getProject() );

            ClassLoader classLoader = ProjectClassLoader.getProjectClassLoader( javaProject );

            loader = new SuggestionCompletionLoader( classLoader );

            if ( !file.exists() ) {
                InputStream is = new ByteArrayInputStream( "// Header file".getBytes() );
                try {
                    file.create( is,
                                 true,
                                 null );
                } catch ( CoreException e ) {
                    // TODO Handle
                    e.printStackTrace();
                }
            }

            packageEditorInput = new FileEditorInput( file );

            reloadCompletionEngine();

            setPageText( 1,
                         "BRXML Source" );

            setPageText( 2,
                         "Generated DRL (read-only)" );

            setPartName( xmlEditor.getTitle() );
            
        } catch ( PartInitException e ) {
            e.printStackTrace();
        }
    }

    private IPath getCurrentDirectoryPath(IEditorInput editorInput) {
        return ((FileEditorInput) editorInput).getFile().getFullPath().removeLastSegments( 1 ).addTrailingSeparator();
    }

    private Composite initializePreviewDRLText() {

        drlPreviewText = new StyledText( getContainer(),
                                         SWT.V_SCROLL | SWT.H_SCROLL );
        drlPreviewText.setEditable( false );
        drlPreviewText.setCaret( null );

        return drlPreviewText;
    }

    private void reloadCompletionEngine() {
        try {

            // Load all .dsl files from current dir
            IPath p = (packageEditorInput).getFile().getFullPath().removeLastSegments( 1 );

            Container folder = (Container) ResourcesPlugin.getWorkspace().getRoot().findMember( p,
                                                                                                false );

            IResource[] files = folder.members( false );

            List dslList = new ArrayList();

            for ( int i = 0; i < files.length; i++ ) {
                if ( files[i].getName().endsWith( ".dsl" ) ) {
                    String contents = getFileContents( (IFile) files[i] );
                    DSLMappingFile dsl = new DSLMappingFile();

                    if ( dsl.parseAndLoad( new StringReader( contents ) ) ) {
                        dslList.add( dsl );
                    } else {
                        //TODO report dsl parse error
                    }
                }
            }

            // Load suggestion engine
            String str = getFileContents( packageEditorInput.getFile() );

            completion = loader.getSuggestionEngine( str,
                                                     dslList,
                                                     dslList );

        } catch ( Exception e ) {
            // TODO Handle problem with the completion engine reloading
            e.printStackTrace();
        }
    }

    private String getFileContents(IFile file) {

        InputStream is;
        try {
            is = file.getContents();

            StringBuffer out = new StringBuffer();
            byte[] b = new byte[4096];
            for ( int n; (n = is.read( b )) != -1; ) {
                out.append( new String( b,
                                        0,
                                        n ) );
            }

            return out.toString();
        } catch ( CoreException e ) {
            //TODO Report problem with the file loading
            return "";
        } catch ( IOException e ) {
            //TODO Report problem with the file loading
            return "";
        }

    }

    public boolean isDirty() {
        return xmlEditor.isDirty() || guidedEditor.isDirty();
    }

    protected void pageChange(int newPageIndex) {
        super.pageChange( newPageIndex );

        IDocument document = getInputDocument();

        if ( newPageIndex == 0 ) {

            boolean newModel = guidedEditor.getModeller().getModel() == null;
            if ( xmlEditor.isDirty() || newModel ) {
                guidedEditor.setModelXML( document.get() );
                if ( newModel ) {
                    guidedEditor.getModeller().setDirty( false );
                }
            }

            guidedEditor.refresh();

        } else if ( newPageIndex == 1 ) {

            if ( guidedEditor.isDirty() ) {
                document.set( BRXMLPersistence.getInstance().marshal( guidedEditor.getRuleModel() ) );
            }
        } else if ( newPageIndex == 2 ) {

            //Updating main document for proper workflow when page is switched back to "0"
            if ( guidedEditor.isDirty() ) {
                document.set( BRXMLPersistence.getInstance().marshal( guidedEditor.getRuleModel() ) );
            } else if ( xmlEditor.isDirty() ) {
                guidedEditor.setModelXML( document.get() );
            }

            String drl;
            try {
                drl = BRDRLPersistence.getInstance().marshal( guidedEditor.getRuleModel() );
            } catch ( Throwable t ) {

                StringWriter strwriter = new StringWriter();
                t.printStackTrace( new PrintWriter( strwriter ) );
                drl = strwriter.toString();
            }
            drlPreviewText.setText( drl );

        }

    }

    public void doSave(IProgressMonitor monitor) {
        IDocument document = getInputDocument();

        if (xmlEditor.isDirty()) {
            guidedEditor.setModelXML( document.get() );
        } else if ( guidedEditor.isDirty() ) {
            document.set( BRXMLPersistence.getInstance().marshal( guidedEditor.getRuleModel() ) );
        }

        xmlEditor.doSave( monitor );

        guidedEditor.getModeller().setDirty( false );

        guidedEditor.refresh();
        
    }

    private IDocument getInputDocument() {
        IEditorInput input = getEditorInput();
        IDocumentProvider docProvider = DocumentProviderRegistry.getDefault().getDocumentProvider( input );
        IDocument document = docProvider.getDocument( input );
        return document;
    }

    public void doSaveAs() {
        xmlEditor.doSaveAs();
        guidedEditor.getModeller().setDirty( false );
        setPartName( xmlEditor.getTitle() );
        setInput( xmlEditor.getEditorInput() );
        
        guidedEditor.refresh();
        
    }

    public boolean isSaveAsAllowed() {
        return true;
    }

    public void resourceChanged(final IResourceChangeEvent event) {
        if ( event.getType() == IResourceChangeEvent.PRE_CLOSE ) {
            Display.getDefault().asyncExec( new Runnable() {
                public void run() {
                    IWorkbenchPage[] pages = getSite().getWorkbenchWindow().getPages();
                    for ( int i = 0; i < pages.length; i++ ) {
                        if ( ((FileEditorInput) xmlEditor.getEditorInput()).getFile().getProject().equals( event.getResource() ) ) {
                            IEditorPart editorPart = pages[i].findEditor( xmlEditor.getEditorInput() );
                            pages[i].closeEditor( editorPart,
                                                  true );
                        }
                    }
                }
            } );
        }
    }

    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener( this );
        ResourcesPlugin.getWorkspace().removeResourceChangeListener( packageFileTracker );
        super.dispose();
    }

    public void dirtyPropertyChanged() {
        firePropertyChange( IEditorPart.PROP_DIRTY );
        guidedEditor.refresh();
    }

    public SuggestionCompletionEngine getCompletionEngine() {
        return completion;
    }

    private IResourceDelta getRootDelta(IResourceDelta delta) {
        if ( delta.getAffectedChildren().length > 0 ) {
            return getRootDelta( delta.getAffectedChildren()[0] );
        }
        return delta;
    }

}