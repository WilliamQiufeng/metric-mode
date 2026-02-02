import { CheckCircle2, Loader2, FileText, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { getArtifactUrl } from '@/lib/apiService';
import type { Stage3Response } from '@/types/api';

interface FileProgressProps {
  files: Stage3Response['files'];
  done: boolean;
}

const FILE_CONFIG = [
  { key: 'predictions', label: 'Predictions CSV', icon: FileText },
  { key: 'explanation', label: 'Model Explanation', icon: FileText },
  { key: 'report1', label: 'Pipeline Report', icon: FileText },
  { key: 'report2', label: 'Scientific Summary', icon: FileText },
] as const;

export const FileProgress = ({ files, done }: FileProgressProps) => {
  // Find the currently generating file (first one that doesn't exist)
  const currentlyGenerating = FILE_CONFIG.findIndex(
    (config) => !files[config.key as keyof typeof files]
  );

  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium text-foreground">File Generation Progress</h3>
      <div className="grid gap-2">
        {FILE_CONFIG.map((config, index) => {
          const filePath = files[config.key as keyof typeof files];
          const isComplete = !!filePath;
          const isGenerating = index === currentlyGenerating && !done;
          const isPending = index > currentlyGenerating && !done;

          return (
            <div
              key={config.key}
              className={cn(
                'flex items-center justify-between p-3 rounded-lg border transition-all duration-300',
                isComplete && 'border-green-500/50 bg-green-500/5',
                isGenerating && 'border-primary bg-primary/5',
                isPending && 'border-border bg-muted/30'
              )}
            >
              <div className="flex items-center gap-3">
                {isComplete ? (
                  <CheckCircle2 className="h-4 w-4 text-green-500" />
                ) : isGenerating ? (
                  <Loader2 className="h-4 w-4 animate-spin text-primary" />
                ) : (
                  <config.icon className="h-4 w-4 text-muted-foreground" />
                )}
                <span className={cn(
                  'font-medium',
                  isComplete && 'text-green-600',
                  isGenerating && 'text-primary',
                  isPending && 'text-muted-foreground'
                )}>
                  {config.label}
                </span>
              </div>
              <div>
                {isComplete && filePath ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 text-xs"
                    onClick={() => window.open(getArtifactUrl(filePath), '_blank')}
                  >
                    <Download className="h-3 w-3 mr-1" />
                    Download
                  </Button>
                ) : isGenerating ? (
                  <span className="text-xs text-primary animate-pulse">Generating...</span>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
