import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Background, Controls, MarkerType, MiniMap, ReactFlow, useEdgesState, useNodesState } from '@xyflow/react';
import * as dagre from '@dagrejs/dagre';
import '@xyflow/react/dist/style.css';
import './graph.css';

const dispatch = (name, detail) => window.dispatchEvent(new CustomEvent(name, { detail }));

const NODE_WIDTH = 200;
const NODE_HEIGHT = 60;

function Graph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [layoutDirection, setLayoutDirection] = useState('LR');
  const positions = useRef(new Map());
  const pendingPositions = useRef(new Map());
  const flushTimer = useRef(null);
  const isAutoLayouting = useRef(false);

  useEffect(() => {
    const load = event => {
      const graph = event.detail || {};
      setNodes(prev => {
        prev.forEach(node => positions.current.set(node.id, node.position));
        return (graph.nodes || []).map((node, index) => ({
          id: node.id,
          position: positions.current.get(node.id) || node.position
            || { x: 40 + (index % 3) * 280, y: 40 + Math.floor(index / 3) * 160 },
          data: { label: node.label },
          className: node.kind === 'external' ? 'external-node' : 'core-node',
          sourcePosition: 'right',
          targetPosition: 'left',
        }));
      });
      setEdges((graph.edges || []).map(edge => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.label,
        className: edge.kind ? `sddp-${edge.kind}` : '',
        selected: Boolean(edge.selected),
        markerEnd: { type: MarkerType.ArrowClosed },
      })));
    };
    const reloadEdges = event => {
      const list = event.detail || [];
      setEdges(list.map(edge => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.label,
        className: edge.kind ? `sddp-${edge.kind}` : '',
        selected: Boolean(edge.selected),
        markerEnd: { type: MarkerType.ArrowClosed },
      })));
    };
    window.addEventListener('sddp:graph-data', load);
    window.addEventListener('sddp:graph-edges', reloadEdges);
    return () => {
      window.removeEventListener('sddp:graph-data', load);
      window.removeEventListener('sddp:graph-edges', reloadEdges);
    };
  }, [setNodes, setEdges]);

  // Debounced position persistence
  const flushPositions = useCallback(() => {
    if (pendingPositions.current.size === 0) return;
    const updates = [...pendingPositions.current.entries()].map(([id, pos]) => ({
      id, positionX: Math.round(pos.x), positionY: Math.round(pos.y)
    }));
    pendingPositions.current.clear();
    dispatch('sddp:graph-positions', updates);
  }, []);

  const scheduleFlush = useCallback(() => {
    if (flushTimer.current) clearTimeout(flushTimer.current);
    flushTimer.current = setTimeout(flushPositions, 800);
  }, [flushPositions]);

  const handleNodesChange = useCallback((changes, setNodesFn) => {
    setNodesFn(changes);
    changes.forEach(change => {
      if (change.type === 'position' && change.position) {
        pendingPositions.current.set(change.id, change.position);
        scheduleFlush();
      }
    });
  }, [scheduleFlush]);

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

  // Auto-layout using dagre
  const runAutoLayout = useCallback(() => {
    if (!nodes.length) return;
    isAutoLayouting.current = true;

    const g = new dagre.graphlib.Graph({ compound: false });
    g.setGraph({ rankdir: layoutDirection, nodesep: 80, ranksep: 120, edgesep: 20 });
    g.setDefaultEdgeLabel(() => ({}));

    nodes.forEach(node => {
      g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
    });
    edges.forEach(edge => {
      g.setEdge(edge.source, edge.target);
    });

    dagre.layout(g);

    setNodes(ns => ns.map(node => {
      const dagreNode = g.node(node.id);
      if (!dagreNode) return node;
      const pos = { x: dagreNode.x - NODE_WIDTH / 2, y: dagreNode.y - NODE_HEIGHT / 2 };
      pendingPositions.current.set(node.id, pos);
      return { ...node, position: pos };
    }));

    scheduleFlush();
    isAutoLayouting.current = false;
  }, [nodes, edges, layoutDirection, scheduleFlush]);

  const handleDirectionChange = useCallback((dir) => {
    setLayoutDirection(dir);
    runAutoLayout();
  }, [runAutoLayout]);

  const handleLayoutClick = useCallback(() => {
    runAutoLayout();
  }, [runAutoLayout]);

  const handleFitView = useCallback((rf) => {
    if (rf) rf.fitView({ padding: 0.1, duration: 300 });
  }, []);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={handleNodesChange}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      onEdgeClick={onEdgeClick}
      onPaneClick={onPaneClick}
      fitView
      aria-label="Topology editor"
      nodeOrigin={[0.5, 0.5]}
    >
      <Background />
      <Controls />
      <MiniMap
        nodeColor={node => node.data?.kind === 'external' ? '#f472b6' : '#60a5fa'}
        maskColor="rgba(11, 18, 32, 0.6)"
      />
      <div className="graph-toolbar">
        <select value={layoutDirection} onChange={e => handleDirectionChange(e.target.value)} aria-label="Layout direction">
          <option value="LR">Left → Right</option>
          <option value="RL">Right → Left</option>
          <option value="TB">Top → Bottom</option>
          <option value="BT">Bottom → Top</option>
        </select>
        <button onClick={handleLayoutClick} title="Auto-layout" aria-label="Auto-layout">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
        </button>
        <button onClick={() => handleFitView(window.__rfInstance)} title="Fit view" aria-label="Fit view">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
        </button>
      </div>
    </ReactFlow>
  );
}

// Capture ReactFlow instance for fitView
const originalCreateRoot = createRoot;
const root = originalCreateRoot(document.getElementById('graph'));
root.render(<Graph />);