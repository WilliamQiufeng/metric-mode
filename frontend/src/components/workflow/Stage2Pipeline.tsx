import { useEffect, useMemo } from 'react';
import { Button } from '@/components/ui/button';
import { ArrowRight, CheckCircle2, XCircle, Loader2, Circle, FileCode, FileText, AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { CandidateInfo } from '@/types/config';
import { getArtifactDownloadUrl, loadConfig } from '@/lib/fileService';

interface Stage2PipelineProps {
  candidates: CandidateInfo[];
  selectedBest: string | null;
  generatedMlRoot: string;
  onComplete: () => void;
}

const Stage2Pipeline = ({ candidates, selectedBest, generatedMlRoot, onComplete }: Stage2PipelineProps) => {
  // Check if all candidates are done or failed
  const isComplete = useMemo(() => {
    return candidates.every(c => c.status === 'done' || c.status === 'failed' || c.status === 'empty');
  }, [candidates]);

  // Find best candidate
  const bestCandidate = useMemo(() => {
    if (!selectedBest) {
      // Auto-detect best from scores
      const doneWithScores = candidates.filter(c => c.status === 'done' && c.metrics?.score !== undefined);
      if (doneWithScores.length === 0) return null;
      return doneWithScores.reduce((best, curr) => 
        (curr.metrics?.score || 0) > (best.metrics?.score || 0) ? curr : best
      );
    }
    return candidates.find(c => c.folderName === selectedBest) || null;
  }, [candidates, selectedBest]);

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'done':
        return <CheckCircle2 className="h-5 w-5 text-green-500" />;
      case 'failed':
        return <XCircle className="h-5 w-5 text-destructive" />;
      case 'running':
        return <Loader2 className="h-5 w-5 text-primary animate-spin" />;
      default:
        return <Circle className="h-5 w-5 text-muted-foreground" />;
    }
  };

  const getNodeStyle = (status: string, isBest: boolean) => {
    if (isBest) return 'border-primary bg-primary/10 ring-2 ring-primary';
    switch (status) {
      case 'done':
        return 'border-green-500 bg-green-500/10';
      case 'failed':
        return 'border-destructive bg-destructive/10';
      case 'running':
        return 'border-primary bg-primary/5';
      default:
        return 'border-border bg-card';
    }
  };

  const getModelName = (prefix: string): string => {
    const names: Record<string, string> = {
      'candidate_01_': 'Model 1',
      'candidate_02_': 'Model 2',
      'candidate_03_': 'Model 3',
      'candidate_04_': 'Model 4',
    };
    return names[prefix] || prefix;
  };

  const hasAnyCandidate = candidates.some(c => c.status !== 'empty');

  return (
    <div className="h-[calc(100vh-140px)] overflow-auto p-6">
      <div className="mx-auto max-w-5xl">
        {/* Header */}
        <div className="mb-8 text-center">
          <h2 className="text-2xl font-bold text-foreground">Model Training Pipeline</h2>
          <p className="mt-2 text-muted-foreground">
            {hasAnyCandidate 
              ? 'Training and evaluating candidate models' 
              : 'Waiting for candidates to start...'}
          </p>
        </div>

        {!hasAnyCandidate ? (
          /* Waiting state */
          <div className="flex flex-col items-center py-16 gap-6">
            <Loader2 className="h-12 w-12 text-primary animate-spin" />
            <p className="text-muted-foreground">
              Scanning for candidate folders in generated_ml/...
            </p>
            <div className="flex items-center gap-4">
              {candidates.map((c, i) => (
                <div key={c.prefix} className="flex items-center">
                  <div className="flex flex-col items-center">
                    <div className="h-16 w-24 rounded-lg border-2 border-dashed border-muted-foreground/30 flex items-center justify-center">
                      <span className="text-xs text-muted-foreground">{getModelName(c.prefix)}</span>
                    </div>
                  </div>
                  {i < 3 && (
                    <div className="w-8 h-0.5 bg-muted-foreground/30 mx-1" />
                  )}
                </div>
              ))}
            </div>
          </div>
        ) : (
          /* Pipeline Visualization */
          <div className="space-y-8">
            {/* Pipeline Flow */}
            <div className="relative">
              {/* Connection Line */}
              <div className="absolute top-1/2 left-0 right-0 h-1 bg-gradient-to-r from-muted via-muted-foreground/20 to-muted -translate-y-1/2 z-0" />
              
              {/* Model Nodes */}
              <div className="relative z-10 flex justify-between">
                {candidates.map((candidate, index) => {
                  const isBest = bestCandidate?.prefix === candidate.prefix;
                  const displayName = candidate.folderName 
                    ? candidate.folderName.replace(candidate.prefix, '').substring(0, 12)
                    : getModelName(candidate.prefix);
                  
                  return (
                    <div
                      key={candidate.prefix}
                      className="flex flex-col items-center"
                    >
                      {/* Node */}
                      <div
                        className={cn(
                          'relative w-32 rounded-xl border-2 p-4 transition-all duration-300',
                          getNodeStyle(candidate.status, isBest)
                        )}
                      >
                        {/* Best Badge */}
                        {isBest && (
                          <div className="absolute -top-3 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-full bg-primary text-primary-foreground text-xs font-medium">
                            Best
                          </div>
                        )}
                        
                        {/* Status Icon */}
                        <div className="flex justify-center mb-2">
                          {getStatusIcon(candidate.status)}
                        </div>
                        
                        {/* Model Name */}
                        <p className="text-sm font-medium text-center text-foreground truncate" title={candidate.folderName || ''}>
                          {displayName}
                        </p>
                        
                        {/* Score */}
                        {candidate.status === 'done' && candidate.metrics?.score !== undefined && (
                          <p className="mt-1 text-center font-mono text-xs text-green-600">
                            {Number(candidate.metrics.score).toFixed(4)}
                          </p>
                        )}
                        
                        {/* Error */}
                        {candidate.status === 'failed' && (
                          <p className="mt-1 text-center text-xs text-destructive flex items-center justify-center gap-1">
                            <AlertTriangle className="h-3 w-3" />
                            Failed
                          </p>
                        )}
                        
                        {/* Running Animation */}
                        {candidate.status === 'running' && (
                          <div className="mt-2 h-1 bg-muted rounded-full overflow-hidden">
                            <div className="h-full w-1/2 bg-primary rounded-full animate-pulse" />
                          </div>
                        )}

                        {/* Artifacts indicators */}
                        {candidate.status !== 'empty' && (
                          <div className="mt-2 flex justify-center gap-1">
                            {candidate.artifacts.modelPy && (
                              <span title="model.py">
                                <FileCode className="h-3 w-3 text-muted-foreground" />
                              </span>
                            )}
                            {candidate.artifacts.testPy && (
                              <span title="test.py">
                                <FileCode className="h-3 w-3 text-muted-foreground" />
                              </span>
                            )}
                            {candidate.artifacts.metricsJson && (
                              <span title="metrics.json">
                                <FileText className="h-3 w-3 text-green-500" />
                              </span>
                            )}
                          </div>
                        )}
                      </div>
                      
                      {/* Step Number */}
                      <div className={cn(
                        'mt-3 w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium',
                        candidate.status === 'done' ? 'bg-green-500 text-white' :
                        candidate.status === 'failed' ? 'bg-destructive text-white' :
                        candidate.status === 'running' ? 'bg-primary text-primary-foreground' :
                        'bg-muted text-muted-foreground'
                      )}>
                        {index + 1}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Status Summary */}
            <div className="flex justify-center gap-8 pt-4">
              <div className="flex items-center gap-2">
                <CheckCircle2 className="h-4 w-4 text-green-500" />
                <span className="text-sm text-muted-foreground">
                  {candidates.filter(m => m.status === 'done').length} Done
                </span>
              </div>
              <div className="flex items-center gap-2">
                <XCircle className="h-4 w-4 text-destructive" />
                <span className="text-sm text-muted-foreground">
                  {candidates.filter(m => m.status === 'failed').length} Failed
                </span>
              </div>
              <div className="flex items-center gap-2">
                <Loader2 className="h-4 w-4 text-primary" />
                <span className="text-sm text-muted-foreground">
                  {candidates.filter(m => m.status === 'running').length} Running
                </span>
              </div>
            </div>

            {/* Complete Actions */}
            {isComplete && bestCandidate && selectedBest && (
              <div className="mt-8 p-6 rounded-xl border-2 border-primary bg-primary/5 text-center">
                <CheckCircle2 className="mx-auto h-10 w-10 text-primary" />
                <h3 className="mt-3 text-lg font-semibold text-foreground">
                  Training Complete!
                </h3>
                <p className="mt-1 text-muted-foreground">
                  Best model: <span className="font-medium text-primary">{bestCandidate.folderName}</span>
                  {bestCandidate.metrics?.score !== undefined && (
                    <> with score <span className="font-mono text-primary">{Number(bestCandidate.metrics.score).toFixed(4)}</span></>
                  )}
                </p>
                <Button onClick={onComplete} className="mt-4 gap-2">
                  Proceed to Results
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            )}

            {isComplete && !bestCandidate && (
              <div className="mt-8 p-6 rounded-xl border-2 border-destructive bg-destructive/5 text-center">
                <XCircle className="mx-auto h-10 w-10 text-destructive" />
                <h3 className="mt-3 text-lg font-semibold text-foreground">
                  All Models Failed
                </h3>
                <p className="mt-1 text-muted-foreground">
                  Please check your configuration and try again.
                </p>
              </div>
            )}

            {/* Waiting for best selection */}
            {isComplete && candidates.some(c => c.status === 'done') && !selectedBest && (
              <div className="mt-8 p-6 rounded-xl border-2 border-muted bg-muted/5 text-center">
                <Loader2 className="mx-auto h-10 w-10 text-muted-foreground animate-spin" />
                <h3 className="mt-3 text-lg font-semibold text-foreground">
                  Waiting for Best Model Selection
                </h3>
                <p className="mt-1 text-muted-foreground">
                  Watching for selected_best.txt...
                </p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default Stage2Pipeline;
