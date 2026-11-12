import React, { useCallback, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { Background, Controls, MarkerType, ReactFlow, useEdgesState, useNodesState } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import './graph.css';

function Graph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  useEffect(() => {
    const load = event => {
      const graph = event.detail;
      setNodes(graph.nodes.map((node, index) => ({
        id: node.id,
        position: { x: 40 + (index % 3) * 240, y: 40 + Math.floor(index / 3) * 140 },
        data: { label: `${node.name} · ${node.external ? 'External' : 'Core'}` },
        className: node.external ? 'external-node' : 'core-node',
      })));
      setEdges(graph.edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target,
        label: edge.transportType, markerEnd: { type: MarkerType.ArrowClosed } })));
    };
    window.addEventListener('sddp:topology', load);
    return () => window.removeEventListener('sddp:topology', load);
  }, [setNodes, setEdges]);
  const onConnect = useCallback(connection => {
    window.dispatchEvent(new CustomEvent('sddp:connection-selected', { detail: connection }));
  }, []);
  return <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
    onConnect={onConnect} fitView aria-label="Редактор топологии">
    <Background /><Controls />
  </ReactFlow>;
}

createRoot(document.getElementById('graph')).render(<Graph />);
