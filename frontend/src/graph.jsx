import React, { useCallback, useEffect, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { Background, Controls, MarkerType, MiniMap, ReactFlow, useEdgesState, useNodesState } from '@xyflow/react';
import * as dagre from '@dagrejs/dagre';
import '@xyflow/react/dist/style.css';
import './graph.css';

const dispatch = (name, detail) => window.dispatchEvent(new CustomEvent(name, { detail }));

const NODE_WIDTH = 200;
const NODE_HEIGHT = 48;

const toEdge = edge => ({
  id: edge.id,
  source: edge.source,
  target: edge.target,
  label: edge.label,
  className: edge.kind ? `sddp-${edge.kind}` : '',
  selected: Boolean(edge.selected),
  markerEnd: { type: MarkerType.ArrowClosed },
});

function Graph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const positions = useRef(new Map());
  const pending = useRef(new Map());
  const flushTimer = useRef(null);
  const flow = useRef(null);

  const flushPositions = useCallback(() => {
    if (!pending.current.size) return;
    const updates = [...pending.current.entries()].map(([id, position]) => ({
      id,
      positionX: Math.round(position.x),
      positionY: Math.round(position.y),
    }));
    pending.current.clear();
    dispatch('sddp:graph-positions', updates);
  }, []);

  const queuePosition = useCallback((id, position) => {
    pending.current.set(id, position);
    if (flushTimer.current) clearTimeout(flushTimer.current);
    flushTimer.current = setTimeout(flushPositions, 400);
  }, [flushPositions]);

  useEffect(() => () => clearTimeout(flushTimer.current), []);

  // The host page owns the data; the bundle only renders it.
  useEffect(() => {
    const load = event => {
      const graph = event.detail || {};
      setNodes(current => {
        current.forEach(node => positions.current.set(node.id, node.position));
        return (graph.nodes || []).map((node, index) => ({
          id: node.id,
          position: positions.current.get(node.id)
            || node.position
            || { x: 40 + (index % 3) * 280, y: 40 + Math.floor(index / 3) * 170 },
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          data: { label: node.label },
          className: node.kind === 'external' ? 'external-node' : 'core-node',
          sourcePosition: 'right',
          targetPosition: 'left',
        }));
      });
      setEdges((graph.edges || []).map(toEdge));
      setTimeout(() => flow.current?.fitView({ padding: 0.15, maxZoom: 1.25 }), 60);
    };
    const reloadEdges = event => setEdges((event.detail || []).map(toEdge));
    window.addEventListener('sddp:graph-data', load);
    window.addEventListener('sddp:graph-edges', reloadEdges);
    return () => {
      window.removeEventListener('sddp:graph-data', load);
      window.removeEventListener('sddp:graph-edges', reloadEdges);
    };
  }, [setNodes, setEdges]);

  // Persist the final position once the user releases the node.
  const onNodeDragStop = useCallback((event, node) => {
    if (node?.id && node.position) queuePosition(node.id, node.position);
  }, [queuePosition]);

  const onConnect = useCallback(connection => {
    if (!connection?.source || !connection?.target) return;
    dispatch('sddp:graph-connect', { source: connection.source, target: connection.target });
  }, []);

  const onEdgeClick = useCallback((event, edge) => {
    dispatch('sddp:graph-edge-click', { id: edge.id });
  }, []);

  const onPaneClick = useCallback(() => {
    dispatch('sddp:graph-edge-click', { id: null });
  }, []);

  const runAutoLayout = useCallback(direction => {
    if (!nodes.length) return;
    const graph = new dagre.graphlib.Graph({ compound: false });
    graph.setGraph({ rankdir: direction || 'LR', nodesep: 70, ranksep: 130, edgesep: 20, marginx: 40, marginy: 40 });
    graph.setDefaultEdgeLabel(() => ({}));
    nodes.forEach(node => graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
    edges.forEach(edge => graph.setEdge(edge.source, edge.target));
    dagre.layout(graph);

    const next = nodes.map(node => {
      const point = graph.node(node.id);
      if (!point) return node;
      const position = { x: Math.round(point.x - NODE_WIDTH / 2), y: Math.round(point.y - NODE_HEIGHT / 2) };
      pending.current.set(node.id, position);
      return { ...node, position };
    });
    setNodes(next);
    flushPositions();
    setTimeout(() => flow.current?.fitView({ padding: 0.15, maxZoom: 1.25 }), 60);
  }, [nodes, edges, setNodes, flushPositions]);

  useEffect(() => {
    const autoLayout = event => runAutoLayout(event.detail?.direction);
    const fitView = () => flow.current?.fitView({ padding: 0.15, maxZoom: 1.25 });
    window.addEventListener('sddp:graph-auto-layout', autoLayout);
    window.addEventListener('sddp:graph-fit-view', fitView);
    return () => {
      window.removeEventListener('sddp:graph-auto-layout', autoLayout);
      window.removeEventListener('sddp:graph-fit-view', fitView);
    };
  }, [runAutoLayout]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onNodeDragStop={onNodeDragStop}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      onEdgeClick={onEdgeClick}
      onPaneClick={onPaneClick}
      onInit={instance => { flow.current = instance; }}
      aria-label="Topology editor"
    >
      <Background />
      <Controls />
      <MiniMap
        nodeColor={node => String(node.className || '').includes('external') ? '#f472b6' : '#60a5fa'}
        maskColor="rgba(11, 18, 32, 0.6)"
      />
    </ReactFlow>
  );
}

createRoot(document.getElementById('graph')).render(<Graph />);
