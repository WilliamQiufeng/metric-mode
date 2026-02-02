import { Check } from 'lucide-react';
import type { WorkflowStage } from '@/types/workflow';
import { cn } from '@/lib/utils';

interface WorkflowStepperProps {
  currentStage: WorkflowStage;
}

const stages = [
  { id: 'stage1', label: 'Intake', description: 'Data & Configuration' },
  { id: 'stage2', label: 'Build Pipeline', description: 'Model Generation' },
  { id: 'stage3', label: 'Results & Reports', description: 'Predictions & PDFs' },
];

const stageIndex = (stage: WorkflowStage): number => {
  return stages.findIndex((s) => s.id === stage);
};

const WorkflowStepper = ({ currentStage }: WorkflowStepperProps) => {
  const currentIndex = stageIndex(currentStage);

  return (
    <nav aria-label="Workflow progress">
      <ol className="flex items-center justify-between">
        {stages.map((stage, index) => {
          const isCompleted = index < currentIndex;
          const isCurrent = index === currentIndex;
          const isUpcoming = index > currentIndex;

          return (
            <li key={stage.id} className="flex flex-1 items-center">
              <div className="flex flex-col items-center">
                <div
                  className={cn(
                    'flex h-10 w-10 items-center justify-center rounded-full border-2 text-sm font-medium transition-colors',
                    isCompleted && 'border-primary bg-primary text-primary-foreground',
                    isCurrent && 'border-primary bg-background text-primary',
                    isUpcoming && 'border-muted bg-background text-muted-foreground'
                  )}
                >
                  {isCompleted ? (
                    <Check className="h-5 w-5" />
                  ) : (
                    index + 1
                  )}
                </div>
                <div className="mt-2 text-center">
                  <p
                    className={cn(
                      'text-sm font-medium',
                      isCurrent ? 'text-foreground' : 'text-muted-foreground'
                    )}
                  >
                    {stage.label}
                  </p>
                  <p className="hidden text-xs text-muted-foreground sm:block">
                    {stage.description}
                  </p>
                </div>
              </div>

              {index < stages.length - 1 && (
                <div
                  className={cn(
                    'mx-4 h-0.5 flex-1',
                    index < currentIndex ? 'bg-primary' : 'bg-muted'
                  )}
                />
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
};

export default WorkflowStepper;
