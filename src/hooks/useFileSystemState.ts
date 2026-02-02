// Hook for polling filesystem state
// Polls every 1 second and returns the current state

import { useState, useEffect, useCallback } from 'react';
import type { FileSystemState } from '@/types/config';
import { createEmptyFileSystemState } from '@/types/config';
import { pollFileSystemState, loadConfig } from '@/lib/fileService';

interface UseFileSystemStateOptions {
  pollInterval?: number;  // Default: 1000ms
  enabled?: boolean;      // Default: true
}

export function useFileSystemState(options: UseFileSystemStateOptions = {}) {
  const { pollInterval = 1000, enabled = true } = options;
  
  const [state, setState] = useState<FileSystemState>(createEmptyFileSystemState());
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [configLoaded, setConfigLoaded] = useState(false);

  // Load config on mount
  useEffect(() => {
    loadConfig()
      .then(() => setConfigLoaded(true))
      .catch(err => setError(`Failed to load config: ${err.message}`));
  }, []);

  // Poll filesystem state
  const poll = useCallback(async () => {
    if (!configLoaded) return;
    
    try {
      const newState = await pollFileSystemState();
      setState(newState);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to poll filesystem');
    } finally {
      setIsLoading(false);
    }
  }, [configLoaded]);

  // Set up polling interval
  useEffect(() => {
    if (!enabled || !configLoaded) return;

    // Initial poll
    poll();

    // Set up interval
    const intervalId = setInterval(poll, pollInterval);

    return () => clearInterval(intervalId);
  }, [enabled, configLoaded, poll, pollInterval]);

  // Derived state helpers
  const currentStage = useCallback((): 'stage1' | 'stage2' | 'stage3' => {
    if (state.selectedBest) return 'stage3';
    if (state.checklistOk) return 'stage2';
    return 'stage1';
  }, [state.checklistOk, state.selectedBest]);

  const stage2Complete = useCallback((): boolean => {
    return state.candidates.some(c => c.status === 'done') && !!state.selectedBest;
  }, [state.candidates, state.selectedBest]);

  return {
    state,
    isLoading,
    error,
    configLoaded,
    
    // Helpers
    currentStage: currentStage(),
    stage2Complete: stage2Complete(),
    
    // Manual refresh
    refresh: poll,
  };
}
