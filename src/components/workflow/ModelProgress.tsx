import { CheckCircle2, XCircle, Loader2, Clock } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ModelInfo } from '@/types/api';

interface ModelProgressProps {
  models: ModelInfo[];
}

export const ModelProgress = ({ models }: ModelProgressProps) => {
  const getStatusIcon = (status: ModelInfo['status']) => {
    switch (status) {
      case 'running':
        return <Loader2 className="h-4 w-4 animate-spin text-primary" />;
      case 'success':
        return <CheckCircle2 className="h-4 w-4 text-green-500" />;
      case 'failed':
        return <XCircle className="h-4 w-4 text-destructive" />;
      default:
        return <Clock className="h-4 w-4 text-muted-foreground" />;
    }
  };

  const getStatusClass = (status: ModelInfo['status']) => {
    switch (status) {
      case 'running':
        return 'border-primary bg-primary/5';
      case 'success':
        return 'border-green-500/50 bg-green-500/5';
      case 'failed':
        return 'border-destructive/50 bg-destructive/5';
      default:
        return 'border-border bg-muted/30';
    }
  };

  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium text-foreground">Model Training Progress</h3>
      <div className="grid gap-2">
        {models.map((model, index) => (
          <div
            key={model.name}
            className={cn(
              'flex items-center justify-between p-3 rounded-lg border transition-all duration-300',
              getStatusClass(model.status)
            )}
          >
            <div className="flex items-center gap-3">
              <span className="text-xs text-muted-foreground font-mono">#{index + 1}</span>
              {getStatusIcon(model.status)}
              <span className={cn(
                'font-medium',
                model.status === 'running' && 'text-primary',
                model.status === 'success' && 'text-green-600',
                model.status === 'failed' && 'text-destructive',
                model.status === 'pending' && 'text-muted-foreground'
              )}>
                {model.name}
              </span>
            </div>
            <div className="text-right">
              {model.status === 'running' && (
                <span className="text-xs text-primary animate-pulse">Training...</span>
              )}
              {model.status === 'success' && model.score !== undefined && (
                <span className="text-xs font-mono text-green-600">
                  Score: {model.score.toFixed(4)}
                </span>
              )}
              {model.status === 'failed' && model.error && (
                <span className="text-xs text-destructive">{model.error}</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
