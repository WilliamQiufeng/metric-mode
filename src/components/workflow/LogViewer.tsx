import { useRef, useEffect } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import type { LogEvent } from '@/types/api';

interface LogViewerProps {
  logs: LogEvent[];
  className?: string;
  maxHeight?: string;
}

const LogViewer = ({ logs, className, maxHeight = '300px' }: LogViewerProps) => {
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new logs
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs]);

  const formatTimestamp = (ts: string) => {
    const date = new Date(ts);
    return date.toLocaleTimeString('en-US', { 
      hour12: false, 
      hour: '2-digit', 
      minute: '2-digit', 
      second: '2-digit' 
    });
  };

  return (
    <div 
      className={cn(
        'rounded-lg border border-border bg-muted/30 font-mono text-xs',
        className
      )}
    >
      <ScrollArea 
        className="p-3" 
        style={{ height: maxHeight }}
      >
        <div ref={scrollRef} className="space-y-1">
          {logs.length === 0 && (
            <p className="text-muted-foreground">Waiting for logs...</p>
          )}
          {logs.map((log, index) => (
            <div key={index} className="flex gap-2">
              <span className="shrink-0 text-muted-foreground">
                {formatTimestamp(log.timestamp)}
              </span>
              
              {log.type === 'log' && log.line && (
                <span className={cn(
                  log.stream === 'stderr' ? 'text-destructive' : 'text-foreground'
                )}>
                  {log.line}
                </span>
              )}
              
              {log.type === 'status' && log.substage && (
                <span className="text-primary">
                  [{log.stage?.toUpperCase()}] {log.substage} 
                  {log.progress !== undefined && (
                    <Badge variant="secondary" className="ml-2 text-xs">
                      {log.progress}%
                    </Badge>
                  )}
                </span>
              )}
              
              {log.type === 'error' && (
                <span className="text-destructive">
                  [ERROR] {log.error}
                </span>
              )}
              
              {log.type === 'complete' && (
                <span className="text-primary font-medium">
                  ✓ Workflow complete
                </span>
              )}
            </div>
          ))}
        </div>
      </ScrollArea>
    </div>
  );
};

export default LogViewer;
