import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

interface LoadingAnimationProps {
  message?: string;
  className?: string;
}

export const LoadingAnimation = ({ message = 'Processing...', className }: LoadingAnimationProps) => {
  return (
    <div className={cn('flex flex-col items-center justify-center py-8 gap-4', className)}>
      <div className="relative">
        {/* Outer spinning ring */}
        <div className="absolute inset-0 rounded-full border-2 border-primary/20 animate-ping" />
        {/* Inner spinner */}
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
      <p className="text-sm text-muted-foreground animate-pulse">{message}</p>
    </div>
  );
};

interface PulsingDotsProps {
  className?: string;
}

export const PulsingDots = ({ className }: PulsingDotsProps) => {
  return (
    <div className={cn('flex items-center gap-1', className)}>
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className="h-2 w-2 rounded-full bg-primary animate-bounce"
          style={{ animationDelay: `${i * 150}ms` }}
        />
      ))}
    </div>
  );
};

interface ProgressBarAnimatedProps {
  progress: number;
  className?: string;
}

export const ProgressBarAnimated = ({ progress, className }: ProgressBarAnimatedProps) => {
  return (
    <div className={cn('w-full h-2 bg-muted rounded-full overflow-hidden', className)}>
      <div
        className="h-full bg-primary rounded-full transition-all duration-500 ease-out relative overflow-hidden"
        style={{ width: `${progress}%` }}
      >
        {/* Shimmer effect */}
        <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent animate-shimmer" />
      </div>
    </div>
  );
};
