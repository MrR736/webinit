package com.mrr736.webinit.test;

import android.os.Bundle;

import com.mrr736.webinit.WebInit;

public class MainActivity extends WebInit {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Init("webinit.pak",8080,"127.0.0.1");
		/* WebInit performs the actual initialization. */
		super.onCreate(savedInstanceState);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}
}
