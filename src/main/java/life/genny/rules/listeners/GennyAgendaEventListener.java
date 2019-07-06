package life.genny.rules.listeners;

import org.drools.core.WorkingMemory;
import org.drools.core.event.ActivationCancelledEvent;
import org.drools.core.event.ActivationCreatedEvent;
import org.drools.core.event.AfterActivationFiredEvent;
import org.drools.core.event.BeforeActivationFiredEvent;
import org.kie.api.event.rule.AgendaGroupPoppedEvent;
import org.kie.api.event.rule.AgendaGroupPushedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.event.rule.RuleFlowGroupDeactivatedEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;

public class GennyAgendaEventListener extends DefaultAgendaEventListener{


        public void activationCreated(ActivationCreatedEvent event,

                WorkingMemory workingMemory){

        }

        public void activationCancelled(ActivationCancelledEvent event,

                  WorkingMemory workingMemory){

        }

        public void beforeActivationFired(BeforeActivationFiredEvent event,

                    WorkingMemory workingMemory) {

        }

        public void afterActivationFired(AfterActivationFiredEvent event,

                   WorkingMemory workingMemory) {

        }

        public void agendaGroupPopped(AgendaGroupPoppedEvent event,

                WorkingMemory workingMemory) {

        }



        public void agendaGroupPushed(AgendaGroupPushedEvent event,

                WorkingMemory workingMemory) {

        }

        public void beforeRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event,

                           WorkingMemory workingMemory) {

        }

     
        public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event,

                WorkingMemory workingMemory) {
        	super.afterRuleFlowGroupActivated(event);
            workingMemory.fireAllRules();

        }

        public void beforeRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event,

                             WorkingMemory workingMemory) {

        }

        public void afterRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event,

                            WorkingMemory workingMemory) {

        }



}