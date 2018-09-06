package org.insightech.er.editor.controller.command.dbimport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.draw2d.geometry.Insets;
import org.eclipse.draw2d.graph.DirectedGraph;
import org.eclipse.draw2d.graph.DirectedGraphLayout;
import org.eclipse.draw2d.graph.Edge;
import org.eclipse.draw2d.graph.Node;
import org.insightech.er.editor.controller.command.diagram_contents.element.node.AbstractCreateElementCommand;
import org.insightech.er.editor.model.ERDiagram;
import org.insightech.er.editor.model.diagram_contents.DiagramContents;
import org.insightech.er.editor.model.diagram_contents.element.connection.Bendpoint;
import org.insightech.er.editor.model.diagram_contents.element.connection.ConnectionElement;
import org.insightech.er.editor.model.diagram_contents.element.connection.Relation;
import org.insightech.er.editor.model.diagram_contents.element.node.Location;
import org.insightech.er.editor.model.diagram_contents.element.node.NodeElement;
import org.insightech.er.editor.model.diagram_contents.element.node.table.ERTable;
import org.insightech.er.editor.model.diagram_contents.element.node.table.TableView;
import org.insightech.er.editor.model.diagram_contents.element.node.table.column.NormalColumn;
import org.insightech.er.editor.model.diagram_contents.not_element.group.ColumnGroup;
import org.insightech.er.editor.model.diagram_contents.not_element.group.GroupSet;
import org.insightech.er.editor.model.diagram_contents.not_element.sequence.Sequence;
import org.insightech.er.editor.model.diagram_contents.not_element.sequence.SequenceSet;
import org.insightech.er.editor.model.diagram_contents.not_element.tablespace.Tablespace;
import org.insightech.er.editor.model.diagram_contents.not_element.tablespace.TablespaceSet;
import org.insightech.er.editor.model.diagram_contents.not_element.trigger.Trigger;
import org.insightech.er.editor.model.diagram_contents.not_element.trigger.TriggerSet;

public class ImportTableCommand extends AbstractCreateElementCommand {

    private static final int AUTO_GRAPH_LIMIT = 100;

    private static final int ORIGINAL_X = 20;
    private static final int ORIGINAL_Y = 20;

    private static final int DISTANCE_X = 300;
    private static final int DISTANCE_Y = 300;

    private final SequenceSet sequenceSet;

    private final TriggerSet triggerSet;

    private final TablespaceSet tablespaceSet;

    private final GroupSet columnGroupSet;

    private final List<NodeElement> nodeElementList;

    private final List<Sequence> sequences;

    private final List<Trigger> triggers;

    private final List<Tablespace> tablespaces;

    private final List<ColumnGroup> columnGroups;

    public ImportTableCommand(final ERDiagram diagram, final List<NodeElement> nodeElementList, final List<Sequence> sequences, final List<Trigger> triggers, final List<Tablespace> tablespaces, final List<ColumnGroup> columnGroups) {
        super(diagram);

        this.nodeElementList = nodeElementList;
        this.sequences = sequences;
        this.triggers = triggers;
        this.tablespaces = tablespaces;
        this.columnGroups = columnGroups;

        final DiagramContents diagramContents = this.diagram.getDiagramContents();

        sequenceSet = diagramContents.getSequenceSet();
        triggerSet = diagramContents.getTriggerSet();
        tablespaceSet = diagramContents.getTablespaceSet();
        columnGroupSet = diagramContents.getGroups();

        decideLocation();
    }

    @SuppressWarnings("unchecked")
    private void decideLocation() {

        if (nodeElementList.size() < AUTO_GRAPH_LIMIT) {
            final DirectedGraph graph = new DirectedGraph();

            final Map<NodeElement, Node> nodeElementNodeMap = new HashMap<NodeElement, Node>();

            final int fontSize = diagram.getFontSize();

            final Insets insets = new Insets(5 * fontSize, 10 * fontSize, 35 * fontSize, 20 * fontSize);

            for (final NodeElement nodeElement : nodeElementList) {
                final Node node = new Node();
                if (nodeElement instanceof ERTable) {
                    ERTable table = (ERTable) nodeElement;
                    node.setPadding(new Insets(table.getX() * table.getFontSize(), table.getY() * table.getFontSize(),
                            table.getHeight() * table.getFontSize(), table.getWidth() * table.getFontSize()));
                } else {
                    node.setPadding(insets);
                }
                graph.nodes.add(node);
                nodeElementNodeMap.put(nodeElement, node);
            }

            for (final NodeElement nodeElement : nodeElementList) {
                for (final ConnectionElement outgoing : nodeElement.getOutgoings()) {
                    final Node sourceNode = nodeElementNodeMap.get(outgoing.getSource());
                    final Node targetNode = nodeElementNodeMap.get(outgoing.getTarget());
                    if (sourceNode != targetNode) {
                        final Edge edge = new Edge(sourceNode, targetNode);
                        graph.edges.add(edge);
                    }
                }
            }

            final DirectedGraphLayout layout = new DirectedGraphLayout();

            layout.visit(graph);

            for (final NodeElement nodeElement : nodeElementNodeMap.keySet()) {
                final Node node = nodeElementNodeMap.get(nodeElement);

                if (nodeElement.getWidth() == 0) {
                    nodeElement.setLocation(new Location(node.x, node.y, -1, -1));
                }
            }

        } else {
            final int numX = (int) Math.sqrt(nodeElementList.size());

            int x = ORIGINAL_X;
            int y = ORIGINAL_Y;

            for (final NodeElement nodeElement : nodeElementList) {
                if (nodeElement.getWidth() == 0) {
                    nodeElement.setLocation(new Location(x, y, -1, -1));

                    x += DISTANCE_X;
                    if (x > DISTANCE_X * numX) {
                        x = ORIGINAL_X;
                        y += DISTANCE_Y;
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doExecute() {
        diagram.getEditor().getActiveEditor().removeSelection();

        if (columnGroups != null) {
            for (final ColumnGroup columnGroup : columnGroups) {
                columnGroupSet.add(columnGroup);
            }
        }

        for (final NodeElement nodeElement : nodeElementList) {
            diagram.addNewContent(nodeElement);
            addToCategory(nodeElement);

            if (nodeElement instanceof TableView) {
                for (final NormalColumn normalColumn : ((TableView) nodeElement).getNormalColumns()) {
                    if (normalColumn.isForeignKey()) {
                        for (final Relation relation : normalColumn.getRelationList()) {
                            if (relation.getSourceTableView() == nodeElement) {
                                setSelfRelation(relation);
                            }
                        }
                    }
                }
            }
        }

        for (final Sequence sequence : sequences) {
            sequenceSet.addObject(sequence);
        }

        for (final Trigger trigger : triggers) {
            triggerSet.addObject(trigger);
        }

        for (final Tablespace tablespace : tablespaces) {
            tablespaceSet.addObject(tablespace);
        }

        diagram.refreshChildren();
        diagram.refreshOutline();

        if (category != null) {
            category.refresh();
        }
    }

    private void setSelfRelation(final Relation relation) {
        boolean anotherSelfRelation = false;

        final TableView sourceTable = relation.getSourceTableView();
        for (final Relation otherRelation : sourceTable.getOutgoingRelations()) {
            if (otherRelation == relation) {
                continue;
            }
            if (otherRelation.getSource() == otherRelation.getTarget()) {
                anotherSelfRelation = true;
                break;
            }
        }

        int rate = 0;

        if (anotherSelfRelation) {
            rate = 50;

        } else {
            rate = 100;
        }

        final Bendpoint bendpoint0 = new Bendpoint(rate, rate);
        bendpoint0.setRelative(true);

        final int xp = 100 - (rate / 2);
        final int yp = 100 - (rate / 2);

        relation.setSourceLocationp(100, yp);
        relation.setTargetLocationp(xp, 100);

        relation.addBendpoint(0, bendpoint0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doUndo() {
        diagram.getEditor().getActiveEditor().removeSelection();

        for (final NodeElement nodeElement : nodeElementList) {
            diagram.removeContent(nodeElement);
            removeFromCategory(nodeElement);

            if (nodeElement instanceof TableView) {
                for (final NormalColumn normalColumn : ((TableView) nodeElement).getNormalColumns()) {
                    diagram.getDiagramContents().getDictionary().remove(normalColumn);
                }
            }
        }

        for (final Sequence sequence : sequences) {
            sequenceSet.remove(sequence);
        }

        for (final Trigger trigger : triggers) {
            triggerSet.remove(trigger);
        }

        for (final Tablespace tablespace : tablespaces) {
            tablespaceSet.remove(tablespace);
        }

        if (columnGroups != null) {
            for (final ColumnGroup columnGroup : columnGroups) {
                columnGroupSet.remove(columnGroup);
            }
        }

        diagram.refreshChildren();
        diagram.refreshOutline();

        if (category != null) {
            category.refresh();
        }
    }
}
