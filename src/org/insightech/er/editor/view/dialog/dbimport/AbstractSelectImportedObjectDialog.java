package org.insightech.er.editor.view.dialog.dbimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.TreeNode;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ContainerCheckedTreeViewer;
import org.insightech.er.ResourceString;
import org.insightech.er.common.dialog.AbstractDialog;
import org.insightech.er.common.exception.InputException;
import org.insightech.er.common.widgets.CompositeFactory;
import org.insightech.er.editor.model.ERDiagram;
import org.insightech.er.editor.model.StringObjectModel;
import org.insightech.er.editor.model.dbimport.DBObject;
import org.insightech.er.editor.model.dbimport.DBObjectSet;

public abstract class AbstractSelectImportedObjectDialog extends AbstractDialog {

    private ContainerCheckedTreeViewer viewer;

    protected Button useCommentAsLogicalNameButton;

    private Button mergeWordButton;
    
    private Button mergeTablesButton;

    protected DBObjectSet dbObjectSet;

    protected boolean resultUseCommentAsLogicalName;

    private boolean resultMergeWord;

    private boolean resultMergeTables;

    private List<DBObject> resultSelectedDbObjects;

    public AbstractSelectImportedObjectDialog(final Shell parentShell, final ERDiagram diagram, final DBObjectSet dbObjectSet) {
        super(parentShell);

        this.dbObjectSet = dbObjectSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initialize(final Composite composite) {
        createObjectListComposite(composite);
    }

    private void createObjectListComposite(final Composite parent) {
        final GridData gridData = new GridData();
        gridData.horizontalAlignment = GridData.FILL;
        gridData.grabExcessHorizontalSpace = true;

        final GridLayout gridLayout = new GridLayout();
        gridLayout.numColumns = 3;
        gridLayout.verticalSpacing = 20;

        final Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(gridLayout);
        composite.setLayoutData(gridData);

        viewer = CompositeFactory.createCheckedTreeViewer(this, composite, 300, 1);

        final GridData groupGridData = new GridData();
        groupGridData.horizontalAlignment = GridData.FILL;
        groupGridData.grabExcessHorizontalSpace = true;
        groupGridData.horizontalSpan = 3;

        final GridLayout groupLayout = new GridLayout();
        groupLayout.marginWidth = 15;
        groupLayout.marginHeight = 15;

        final Group group = new Group(composite, SWT.NONE);
        group.setText(ResourceString.getResourceString("label.options"));
        group.setLayoutData(groupGridData);
        group.setLayout(groupLayout);

        initializeOptionGroup(group);
    }

    protected void initializeOptionGroup(final Group group) {
        mergeWordButton = CompositeFactory.createCheckbox(this, group, "label.merge.word", false);
        mergeWordButton.setSelection(true);
        mergeTablesButton = CompositeFactory.createCheckbox(this, group, "label.merge.tables", false);
        mergeTablesButton.setSelection(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void createButtonsForButtonBar(final Composite parent) {
        createButton(parent, IDialogConstants.BACK_ID, IDialogConstants.BACK_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void perfomeOK() throws InputException {
        final Object[] selectedNodes = viewer.getCheckedElements();

        resultSelectedDbObjects = new ArrayList<DBObject>();

        for (int i = 0; i < selectedNodes.length; i++) {
            final Object value = ((TreeNode) selectedNodes[i]).getValue();

            if (value instanceof DBObject) {
                resultSelectedDbObjects.add((DBObject) value);
            }
        }

        resultMergeWord = mergeWordButton.getSelection();
        resultMergeTables = mergeTablesButton.getSelection();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getErrorMessage() {
        if (viewer.getCheckedElements().length == 0) {
            return "error.import.object.empty";
        }

        return null;
    }

    @Override
    protected String getTitle() {
        return "dialog.title.select.import.object";
    }

    @Override
    protected void setData() {
        final List<TreeNode> treeNodeList = createTreeNodeList();

        final TreeNode[] treeNodes = treeNodeList.toArray(new TreeNode[treeNodeList.size()]);
        viewer.setInput(treeNodes);
        viewer.setCheckedElements(treeNodes);
        viewer.expandAll();
    }

    protected List<TreeNode> createTreeNodeList() {
        final List<TreeNode> treeNodeList = new ArrayList<TreeNode>();

        TreeNode topNode = new TreeNode(new StringObjectModel(ResourceString.getResourceString("label.schema")));
        treeNodeList.add(topNode);

        final List<TreeNode> schemaNodeList = new ArrayList<TreeNode>();

        for (final Map.Entry<String, List<DBObject>> entry : dbObjectSet.getSchemaDbObjectListMap().entrySet()) {
            String schemaName = entry.getKey();
            if ("".equals(schemaName)) {
                schemaName = ResourceString.getResourceString("label.none");
            }
            final TreeNode schemaNode = new TreeNode(new StringObjectModel(schemaName));
            schemaNode.setParent(topNode);
            schemaNodeList.add(schemaNode);

            final List<DBObject> dbObjectList = entry.getValue();

            final TreeNode[] objectTypeNodes = new TreeNode[DBObject.ALL_TYPES.length];

            for (int i = 0; i < DBObject.ALL_TYPES.length; i++) {
                objectTypeNodes[i] = new TreeNode(new StringObjectModel(ResourceString.getResourceString("label.object.type." + DBObject.ALL_TYPES[i])));

                final List<TreeNode> objectNodeList = new ArrayList<TreeNode>();

                for (final DBObject dbObject : dbObjectList) {
                    if (DBObject.ALL_TYPES[i].equals(dbObject.getType())) {
                        final TreeNode objectNode = new TreeNode(dbObject);
                        objectNode.setParent(objectTypeNodes[i]);

                        objectNodeList.add(objectNode);
                    }
                }

                objectTypeNodes[i].setChildren(objectNodeList.toArray(new TreeNode[objectNodeList.size()]));
            }

            schemaNode.setChildren(objectTypeNodes);
        }

        topNode.setChildren(schemaNodeList.toArray(new TreeNode[schemaNodeList.size()]));

        topNode = createTopNode(DBObject.TYPE_TABLESPACE, dbObjectSet.getTablespaceList());
        treeNodeList.add(topNode);

        return treeNodeList;
    }

    protected TreeNode createTopNode(final String objectType, final List<DBObject> dbObjectList) {
        final TreeNode treeNode = new TreeNode(new StringObjectModel(ResourceString.getResourceString("label.object.type." + objectType)));
        final List<TreeNode> objectNodeList = new ArrayList<TreeNode>();

        for (final DBObject dbObject : dbObjectList) {
            final TreeNode objectNode = new TreeNode(dbObject);
            objectNode.setParent(treeNode);

            objectNodeList.add(objectNode);
        }

        treeNode.setChildren(objectNodeList.toArray(new TreeNode[objectNodeList.size()]));

        return treeNode;
    }

    public boolean isUseCommentAsLogicalName() {
        return resultUseCommentAsLogicalName;
    }

    public boolean isMergeWord() {
        return resultMergeWord;
    }
    
    public boolean isMergeTables() {
        return resultMergeTables;
    }

    public boolean isMergeGroup() {
        return false;
    }

    public List<DBObject> getSelectedDbObjects() {
        return resultSelectedDbObjects;
    }

}
