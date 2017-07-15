package com.axellience.vuegwtexamples.client.examples.todolist;

import com.axellience.vuegwt.client.Vue;
import com.axellience.vuegwt.jsr69.component.annotations.Component;
import com.axellience.vuegwt.jsr69.component.annotations.Prop;
import com.axellience.vuegwtexamples.client.examples.common.Todo;
import jsinterop.annotations.JsType;

/**
 * @author Adrien Baron
 */
@JsType
@Component
public class TodoComponent extends Vue
{
    @Prop
    public Todo todo;

    @Override
    public void created() {}
}