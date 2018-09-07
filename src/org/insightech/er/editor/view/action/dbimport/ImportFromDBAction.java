package org.insightech.er.editor.view.action.dbimport;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.PlatformUI;
import org.insightech.er.ERDiagramActivator;
import org.insightech.er.ImageKey;
import org.insightech.er.ResourceString;
import org.insightech.er.common.exception.InputException;
import org.insightech.er.db.DBManager;
import org.insightech.er.db.DBManagerFactory;
import org.insightech.er.editor.ERDiagramEditor;
import org.insightech.er.editor.model.ERDiagram;
import org.insightech.er.editor.model.dbimport.DBObjectSet;
import org.insightech.er.editor.model.dbimport.ImportFromDBManagerEclipseBase;
import org.insightech.er.editor.model.dbimport.PreImportFromDBManager;
import org.insightech.er.editor.model.diagram_contents.element.node.NodeElement;
import org.insightech.er.editor.model.diagram_contents.element.node.category.Category;
import org.insightech.er.editor.model.diagram_contents.element.node.table.ERTable;
import org.insightech.er.editor.model.settings.DBSetting;
import org.insightech.er.editor.view.dialog.dbimport.AbstractSelectImportedObjectDialog;
import org.insightech.er.editor.view.dialog.dbimport.ImportDBSettingDialog;
import org.insightech.er.editor.view.dialog.dbimport.SelectImportedObjectFromDBDialog;
import org.insightech.er.editor.view.dialog.dbimport.SelectImportedSchemaDialog;

public class ImportFromDBAction extends AbstractImportAction {

    public static final String ID = ImportFromDBAction.class.getName();

    private DBSetting dbSetting;

    public ImportFromDBAction(final ERDiagramEditor editor) {
        super(ID, ResourceString.getResourceString("action.title.import.db"), editor);
        setImageDescriptor(ERDiagramActivator.getImageDescriptor(ImageKey.DATABASE));
    }

    protected AbstractSelectImportedObjectDialog createSelectImportedObjectDialog(final DBObjectSet dbObjectSet) {
        final ERDiagram diagram = getDiagram();

        return new SelectImportedObjectFromDBDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), diagram, dbObjectSet, getEditorPart());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(final Event event) throws Exception {
        final ERDiagram diagram = getDiagram();

        int step = 0;

        ImportDBSettingDialog settingDialog = null;
        SelectImportedSchemaDialog selectDialog = null;
        PreImportFromDBManager preTableImportManager = null;
        AbstractSelectImportedObjectDialog importDialog = null;

        DBManager manager = null;
        Connection con = null;
        List<String> selectedSchemaList = new ArrayList<String>();
        int dialogResult = -1;

        try {
            while (true) {
                if (step == 0) {
                    settingDialog = new ImportDBSettingDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), diagram);
                    dialogResult = settingDialog.open();

                } else if (step == 1) {
                    if (dialogResult == IDialogConstants.OK_ID) {
                        dbSetting = settingDialog.getDbSetting();
                        manager = DBManagerFactory.getDBManager(dbSetting.getDbsystem());
                        if (con != null) {
                            con.close();
                            con = null;
                        }
                        con = dbSetting.connect();
                    }

                } else if (step == 2) {
                    final List<String> schemaList = manager.getImportSchemaList(con);

                    if (!schemaList.isEmpty()) {
                        selectDialog = new SelectImportedSchemaDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), diagram, dbSetting.getDbsystem(), schemaList, selectedSchemaList);

                        dialogResult = selectDialog.open();

                        selectedSchemaList = selectDialog.getSelectedSchemas();

                    } else {
                        selectedSchemaList.clear();
                    }

                } else if (step == 3) {
                    if (dialogResult == IDialogConstants.OK_ID) {
                        final ProgressMonitorDialog dialog = new ProgressMonitorDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());

                        preTableImportManager = manager.getPreTableImportManager();
                        preTableImportManager.init(con, dbSetting, diagram, selectedSchemaList);

                        try {
                            dialog.run(true, true, preTableImportManager);

                            final Exception e = preTableImportManager.getException();
                            if (e != null) {
                                ERDiagramActivator.showMessageDialog(e.getMessage());
                                throw new InputException("error.jdbc.version");

                            }

                            dialogResult = IDialogConstants.OK_ID;

                        } catch (final InterruptedException e1) {
                            dialogResult = IDialogConstants.BACK_ID;
                        }
                    }

                } else if (step == 4) {
                    final DBObjectSet dbObjectSet = preTableImportManager.getImportObjects();

                    importDialog = createSelectImportedObjectDialog(dbObjectSet);

                    dialogResult = importDialog.open();

                } else if (step == 5) {
                    final ProgressMonitorDialog dialog = new ProgressMonitorDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());
                    final ImportFromDBManagerEclipseBase tableImportManager = (ImportFromDBManagerEclipseBase) manager.getTableImportManager();
                    tableImportManager.init(con, dbSetting, diagram, importDialog.getSelectedDbObjects(), importDialog.isUseCommentAsLogicalName(), importDialog.isMergeWord(), importDialog.isMergeTables());

                    try {
                        dialog.run(true, true, tableImportManager);

                        final Exception e1 = tableImportManager.getException();
                        if (e1 != null) {
                            throw e1;

                        } else {
                            importedNodeElements = new ArrayList<NodeElement>();
                            
                            // TODO Think about more efficient algorithm to compare tables
                            if (tableImportManager.isMergeTables()) {
                                List<ERTable> importedTables = tableImportManager.getImportedTables();
                                
                                List<ERTable> existTables = 
                                        diagram.getDiagramContents().getContents().getTableSet().getList();
                                List<ERTable> existTablesCopy = new ArrayList<>(existTables);
                                for (ERTable imported : importedTables) {
                                	boolean importedFoundInExists = false;
                                    for (ERTable exists : existTablesCopy) {
                                        if (imported.getPhysicalName().equalsIgnoreCase(exists.getPhysicalName())) {
                                            imported.setFontName(exists.getFontName());
                                            imported.setFontSize(exists.getFontSize());
                                            imported.setLocation(exists.getLocation());
                                            imported.setActualLocation(exists.getActualLocation());
                                            removeExistsTableFromDiagram(diagram, exists);
                                            importedNodeElements.add(imported);
                                            importedFoundInExists = true;
                                            break;
                                        }
                                    }
                                    if (!importedFoundInExists) {
                                    	importedNodeElements.add(imported);
                                    }
                                }
                            } else {
                                importedNodeElements.addAll(tableImportManager.getImportedTables());
                            }
                            importedNodeElements.addAll(tableImportManager.getImportedViews());
                            importedSequences = tableImportManager.getImportedSequences();
                            importedTriggers = tableImportManager.getImportedTriggers();
                            importedTablespaces = tableImportManager.getImportedTablespaces();

                            showData();
                            break;
                        }

                    } catch (final InterruptedException e1) {
                        dialogResult = IDialogConstants.BACK_ID;
                    }

                } else {
                    break;
                }

                if (dialogResult == IDialogConstants.OK_ID) {
                    step++;

                } else if (dialogResult == IDialogConstants.BACK_ID) {
                    step--;

                } else {
                    break;
                }
            }

        } finally {
            if (con != null) {
                con.close();
            }
        }

    }

    //TODO make it revertable
	private void removeExistsTableFromDiagram(final ERDiagram diagram, final ERTable exists) {
		diagram.removeContent(exists);
		for (final Category category : diagram.getDiagramContents().getSettings().getCategorySetting().getAllCategories()) {
		    if (category.contains(exists)) {
		        category.remove(exists);
		    }
		}
		diagram.refreshChildren();
	}
}
